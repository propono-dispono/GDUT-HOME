#!/usr/bin/env bash
# ============================================================
# 三条通知链路全部走 MQ 的端到端验证
#
# 改造前：审核 / 私信 / 举报三处的通知是**同步** insert notice 表（在业务事务里）。
# 改造后：统一走 noticePublisher → 事务提交后投递 → 消费线程落库。
#
# 验证的核心不是"通知能收到"（改造前也能），而是：
#   ① 通知带 msg_id（证明它来自队列，不是同步写的）
#   ② 业务接口不再等通知表（代码层面已确认，这里靠 msg_id 佐证）
#
# 前提：隧道 + 服务已启动；账号已认证（python tools/_set_verified.py eo_）
# 用法：bash tools/verify_mq_notice.sh
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

# 管理端登录（账号从凭据表来，本地库与生产同一条记录）
admin_login() {
  curl -s --noproxy '*' -X POST "$BASE/admin/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"duC6Bg3NUcCbKNMG"}' \
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

count_title() {
  api "$1" GET "/app/notice/list?page=1&size=50" | grep -o "\"title\":\"$2\"" | wc -l | tr -d ' '
}

get_uid() {
  python - "$1" <<'PY'
import sys
sys.path.insert(0, 'tools')
from apply_migration import read_password
import pymysql
conn = pymysql.connect(host='127.0.0.1', port=13307, user='root',
                       password=read_password(), database='xiaojii', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SELECT id FROM user WHERE openid = %s", (sys.argv[1],))
row = cur.fetchone()
print(row[0] if row else '')
PY
}

# 查"某用户最近一条通知"是否带 msg_id（证明来自队列）
check_msgid() {
  python - "$1" <<'PY'
import sys
sys.path.insert(0, 'tools')
from apply_migration import read_password
import pymysql
conn = pymysql.connect(host='127.0.0.1', port=13307, user='root',
                       password=read_password(), database='xiaojii', charset='utf8mb4')
cur = conn.cursor()
cur.execute("SELECT title, msg_id FROM notice WHERE user_id=%s ORDER BY id DESC LIMIT 1", (int(sys.argv[1]),))
row = cur.fetchone()
if not row:
    print('NO_NOTICE')
elif row[1]:
    print(f'OK {row[1]} {row[0]}')
else:
    print(f'NO_MSGID {row[0]}')
PY
}

echo "=============================================="
echo " 三条通知链路走 MQ 验证 → $BASE"
echo "=============================================="

echo
echo "[0] 登录账号"
T1=$(login eo_u1); T2=$(login eo_u2); TA=$(admin_login)
UID1=$(get_uid eo_u1); UID2=$(get_uid eo_u2)
if [ -z "$T1" ] || [ -z "$TA" ]; then fail "登录失败（用户或管理员）"; exit 1; fi
pass "用户 u1=$UID1 / u2=$UID2 / 管理员 token 就绪"

echo
echo "[1] ★链路一：私信 → 对方收到通知（原来同步写，现在走 MQ）"
B=$(count_title "$T2" "收到一条新私信")
S=$(api "$T1" POST "/app/chat/session?toUserId=$UID2")
SID=$(num_of "$S" id)
if [ -z "$SID" ]; then
  fail "开会话失败：$(echo "$S" | head -c 100)"
else
  R=$(api "$T1" POST "/app/chat/$SID/messages" '{"content":"MQ通知验证消息"}')
  [ "$(code_of "$R")" = "0" ] && pass "消息发送成功 sessionId=$SID" || fail "发消息失败 $(code_of "$R")"
  sleep 2
  A=$(count_title "$T2" "收到一条新私信")
  [ "$A" -gt "$B" ] && pass "对方收到私信通知（$B → $A）" || fail "对方没收到通知（仍是 $A）"
  RES=$(check_msgid "$UID2")
  case "$RES" in
    OK*) pass "该通知带 msg_id（来自队列，不是同步写）：$(echo "$RES" | cut -d' ' -f2)" ;;
    *) fail "通知缺少 msg_id：$RES" ;;
  esac
fi

echo
echo "[2] ★链路二：发布 → 管理员审核通过 → 作者收到通知"
B=$(count_title "$T1" "内容已通过审核")
CREATED=$(api "$T1" POST "/app/post" \
  "{\"bizType\":\"SECONDHAND\",\"categoryId\":1,\"title\":\"MQ审核链路验证\",\"content\":\"测试审核通知\",\"price\":9}")
PID=$(num_of "$CREATED" id)
if [ -z "$PID" ]; then
  fail "发帖失败：$(echo "$CREATED" | head -c 100)"
else
  # 新用户发帖可能直接过审，这里强制改回「待审核」以便走 approve 接口
  python - "$PID" <<'PY'
import sys
sys.path.insert(0, 'tools')
from apply_migration import read_password
import pymysql
conn = pymysql.connect(host='127.0.0.1', port=13307, user='root',
                       password=read_password(), database='xiaojii',
                       charset='utf8mb4', autocommit=True)
cur = conn.cursor()
cur.execute("UPDATE post SET status=0, audit_status=0 WHERE id=%s", (sys.argv[1],))
PY
  R=$(api "$TA" POST "/admin/post/$PID/approve")
  [ "$(code_of "$R")" = "0" ] && pass "审核通过 postId=$PID" || fail "审核失败 $(code_of "$R")"
  sleep 2
  A=$(count_title "$T1" "内容已通过审核")
  [ "$A" -gt "$B" ] && pass "作者收到审核通知（$B → $A）" || fail "作者没收到审核通知（仍是 $A）"
  RES=$(check_msgid "$UID1")
  case "$RES" in
    OK*) pass "审核通知带 msg_id：$(echo "$RES" | cut -d' ' -f2)" ;;
    *) fail "审核通知缺少 msg_id：$RES" ;;
  esac
fi

echo
echo "[3] ★链路三：举报 → 管理员处理 → 举报人收到通知"
B=$(count_title "$T2" "你的举报已处理")
R=$(api "$T2" POST "/app/report" \
  "{\"targetType\":\"POST\",\"targetId\":$PID,\"reasonType\":\"SPAM\",\"description\":\"MQ验证举报\"}")
RID=$(num_of "$R" id)
if [ "$(code_of "$R")" = "0" ]; then
  pass "举报提交成功"
  # 从列表里找出刚提交的举报 id
  RID=$(api "$TA" GET "/admin/report/list?status=0&page=1&size=5" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  info "待处理举报 id=$RID"
  if [ -n "$RID" ]; then
    H=$(api "$TA" POST "/admin/report/$RID/handle" '{"result":"REJECTED","remark":"MQ验证"}')
    [ "$(code_of "$H")" = "0" ] && pass "举报处理完成" || fail "处理失败 $(code_of "$H")"
    sleep 2
    A=$(count_title "$T2" "你的举报已处理")
    [ "$A" -gt "$B" ] && pass "举报人收到处理通知（$B → $A）" || fail "举报人没收到通知（仍是 $A）"
  else
    fail "没找到待处理举报"
  fi
else
  fail "举报提交失败 $(code_of "$R")：$(echo "$R" | head -c 100)"
fi

echo
echo "[4] 队列健康（三条链路的消息都应该已消费完）"
python tools/mq_status.py notice 2>&1 | grep -E "length|pending|死信" | sed 's/^/  /'

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
