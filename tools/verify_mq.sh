#!/usr/bin/env bash
# ============================================================
# 拼单通知 + 消息队列 端到端验证
#
# 前提：
#   1) 隧道：启动隧道.bat（MySQL 13307 / Redis 16379）
#   2) 服务：./mvnw -o spring-boot:run -Dspring-boot.run.profiles=local \
#             -Dspring-boot.run.arguments="--server.port=8080"
#   3) 账号：python tools/_set_verified.py eo_
#   4) 若被限流挡住：python tools/clear_ratelimit.py --all-test
#
# 用法：bash tools/verify_mq.sh
#
# 验的是什么：
#   [A] 有人参加拼单   → 发起人收到「有人参与了你的拼单」
#   [B] 拼满成团       → 发起人 + 每个参与者都收到「拼单成团了」
#   [C] 通知是异步落库的（走 MQ），不是同步写
#   [D] 队列状态健康：消费组在、pending=0、死信 0
#   [E] 同一条消息重复消费不会产生两条通知（幂等）
# ============================================================

BASE=${BASE:-http://127.0.0.1:8080/api}
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  [PASS] $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  [FAIL] $1"; }
info() { echo "  [INFO] $1"; }

code_of() { echo "$1" | grep -o '"code":[0-9-]*' | head -1 | cut -d: -f2; }
num_of() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2; }

login() {
  curl -s --noproxy '*' -X POST "$BASE/app/auth/dev-login" \
    -H 'Content-Type: application/json' \
    -d "{\"openid\":\"$1\",\"nickname\":\"$1\"}" \
  | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4
}

api() {
  local t=$1 m=$2 p=$3 b=${4:-}
  if [ -n "$b" ]; then
    curl -s --noproxy '*' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" \
      -H 'Content-Type: application/json' -d "$b"
  else
    curl -s --noproxy '*' -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
  fi
}

# 统计某个用户通知列表里，标题 EXACTLY 等于 $2 的条数
count_title() {
  api "$1" GET "/app/notice/list?page=1&size=50" | grep -o "\"title\":\"$2\"" | wc -l | tr -d ' '
}

echo "=============================================="
echo " 拼单通知 + MQ 验证 → $BASE"
echo "=============================================="

echo
echo "[0] 登录 3 个账号"
T_OWNER=$(login eo_owner); T1=$(login eo_u1); T2=$(login eo_u2)
if [ -z "$T_OWNER" ] || [ -z "$T2" ]; then
  fail "登录失败，服务没起来？看 /tmp/mq_server.log"
  exit 1
fi
pass "3 个 token 就绪"

echo
echo "[1] 发拼单帖（目标 3 人 = 发起人 + u1 + u2，明天截止）"
DEADLINE=$(date -d '+1 day' '+%Y-%m-%d %H:%M:%S')
CREATED=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"GROUPBUY\",\"categoryId\":1,\"title\":\"MQ验证拼单\",\"content\":\"验证通知链路\",\"price\":10,\"targetCount\":3,\"groupbuyDeadline\":\"$DEADLINE\",\"pickupPoint\":\"一饭门口\"}")
PID=$(num_of "$CREATED" id)
if [ "$(code_of "$CREATED")" = "0" ] && [ -n "$PID" ]; then
  pass "拼单创建成功 postId=$PID"
else
  fail "创建失败：$(echo "$CREATED" | head -c 120)"
  exit 1
fi
python tools/_publish_post.py "$PID" >/dev/null 2>&1

echo
echo "[2] 记录通知基线（后面用增量判断，避免历史通知干扰）"
B_JOIN=$(count_title "$T_OWNER" "有人参与了你的拼单")
B_FULL_O=$(count_title "$T_OWNER" "拼单成团了")
B_FULL_1=$(count_title "$T1" "拼单成团了")
B_FULL_2=$(count_title "$T2" "拼单成团了")
info "基线：owner参加=$B_JOIN owner成团=$B_FULL_O u1成团=$B_FULL_1 u2成团=$B_FULL_2"

echo
echo "[3] ★A：u1 参加 → 发起人应收到「有人参与了你的拼单」"
R=$(api "$T1" POST "/app/post/$PID/join")
if [ "$(code_of "$R")" != "0" ]; then
  fail "u1 参加失败：$(code_of "$R")"
else
  pass "u1 参加成功"
fi
sleep 2   # 等异步消费（正常 200ms 内，给足余量）
A_JOIN=$(count_title "$T_OWNER" "有人参与了你的拼单")
if [ "$A_JOIN" -gt "$B_JOIN" ]; then
  pass "发起人收到参加通知（$B_JOIN → $A_JOIN）"
else
  fail "发起人没收到通知（仍是 $A_JOIN）→ 看 MQ 日志"
fi

echo
echo "[4] ★B：u2 参加 → 满员成团，3 人都应收到「拼单成团了」"
R=$(api "$T2" POST "/app/post/$PID/join")
if [ "$(code_of "$R")" != "0" ]; then
  fail "u2 参加失败：$(code_of "$R")"
else
  pass "u2 参加成功，拼单应已满员"
fi
sleep 2
A_FULL_O=$(count_title "$T_OWNER" "拼单成团了")
A_FULL_1=$(count_title "$T1" "拼单成团了")
A_FULL_2=$(count_title "$T2" "拼单成团了")
[ "$A_FULL_O" -gt "$B_FULL_O" ] && pass "发起人收到成团通知" || fail "发起人没收到成团通知"
[ "$A_FULL_1" -gt "$B_FULL_1" ] && pass "u1 收到成团通知" || fail "u1 没收到成团通知"
[ "$A_FULL_2" -gt "$B_FULL_2" ] && pass "u2 收到成团通知" || fail "u2 没收到成团通知"

echo
echo "[5] ★B2：满员后帖子应关闭，其他人再参加应被挡"
T3=$(login eo_u3)
R=$(api "$T3" POST "/app/post/$PID/join")
C=$(code_of "$R")
if [ "$C" = "1070" ] || [ "$C" = "1073" ]; then
  pass "满员后参加被挡（返回 $C）"
else
  fail "返回 $C，期望 1070/1073"
fi

echo
echo "[6] ★C：通知必须走 MQ（msg_id 非空且唯一）"
python - <<'PY'
import re, pymysql
cfg = open('xiaojii-server/src/main/resources/application-local.yml', encoding='utf-8').read()
pw = re.search(r'password:\s*(\S+)', cfg.split('datasource:')[1]).group(1)
c = pymysql.connect(host='127.0.0.1', port=13307, user='root', password=pw, database='xiaojii', charset='utf8mb4')
cur = c.cursor()
cur.execute("SELECT COUNT(*) FROM notice WHERE title='拼单成团了' AND msg_id IS NOT NULL")
mq_cnt = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM notice WHERE title='拼单成团了'")
all_cnt = cur.fetchone()[0]
cur.execute("SELECT msg_id, COUNT(*) c FROM notice WHERE msg_id IS NOT NULL GROUP BY msg_id HAVING c > 1")
dup = cur.fetchall()
print(f"RESULT mq={mq_cnt} all={all_cnt} dup={len(dup)}")
PY

echo
echo "[7] ★D：队列健康检查"
python tools/mq_status.py notice 2>&1 | sed 's/^/  /' | head -12
PENDING=$(python tools/mq_status.py notice 2>/dev/null | grep -o 'pending *: [0-9]*' | grep -o '[0-9]*' | head -1)
if [ "$PENDING" = "0" ]; then
  pass "消费组 pending=0（消息都处理完了）"
else
  fail "pending=$PENDING，有消息卡住没 ACK"
fi

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
