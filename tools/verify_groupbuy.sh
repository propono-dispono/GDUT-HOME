#!/usr/bin/env bash
# ============================================================
# 拼单模块并发验证脚本（RocketMQ 之前的基线）
#
# 前提：
#   1) 隧道：双击 启动隧道.bat（MySQL 13307 / Redis 16379）
#   2) 服务：./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
#             -Dspring-boot.run.arguments="--server.port=8080"
#   3) 账号已认证：python tools/_set_verified.py
#
# 用法：bash tools/verify_groupbuy.sh
#
# 流程（target=5，发起人占 1，剩 4 个名额）：
#   u1/u2/u3 并发参加    → 3 个全成功，成员 = 4
#   u1 重复并发参加 5 次 → 成员仍 4（幂等）
#   u4 参加              → 成员 = 5，满员
#   u5 参加              → 1070 已满员
#   u2 并发退出 3 次     → 成员 = 4（只减一次）
#   退出后                → 帖子重新开放
# ============================================================

BASE=${BASE:-http://127.0.0.1:8080/api}
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  [PASS] $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  [FAIL] $1"; }
info() { echo "  [INFO] $1"; }

code_of() { echo "$1" | grep -o '"code":[0-9-]*' | head -1 | cut -d: -f2; }

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

# 成员数 = 返回 JSON 里 userId 出现次数
member_count() {
  api "$T0" GET "/app/post/$PID/groupbuy/members" |
    grep -o '"userId":[0-9]*' | wc -l | tr -d ' '
}

echo "=============================================="
echo " 拼单并发验证 → $BASE"
echo "=============================================="

echo
echo "[0] 登录 6 个测试账号"
T0=$(login vgb_owner); T1=$(login vgb_u1); T2=$(login vgb_u2)
T3=$(login vgb_u3);    T4=$(login vgb_u4); T5=$(login vgb_u5)
if [ -z "$T0" ] || [ -z "$T5" ]; then
  fail "登录失败，服务没起来？"
  exit 1
fi
pass "6 个 token 就绪"

echo
echo "[1] 创建拼单（目标 5 人，明天截止）"
DEADLINE=$(date -d '+1 day' '+%Y-%m-%d %H:%M:%S')
CREATED=$(api "$T0" POST "/app/post" "{\"bizType\":\"GROUPBUY\",\"categoryId\":1,\"title\":\"并发验证\",\"content\":\"并发测试\",\"targetCount\":5,\"groupbuyDeadline\":\"$DEADLINE\",\"pickupPoint\":\"一饭门口\"}")
CODE=$(code_of "$CREATED")
PID=$(echo "$CREATED" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ "$CODE" = "0" ] && [ -n "$PID" ]; then
  pass "创建成功 postId=$PID"
else
  fail "创建失败 code=$CODE → 先跑 python tools/_set_verified.py"
  exit 1
fi

echo
echo "[2] 放行帖子（新用户发帖默认待审核，验并发需要 ONLINE）"
python tools/_publish_post.py "$PID" 2>&1 | head -2

echo
echo "[3] u1/u2/u3 并发参加（抢 4 个名额中的 3 个）"
rm -f /tmp/vgb_j_*.out
i=0
for t in "$T1" "$T2" "$T3"; do
  i=$((i + 1))
  api "$t" POST "/app/post/$PID/join" >"/tmp/vgb_j_$i.out" &
done
wait
OK=0
for f in /tmp/vgb_j_*.out; do [ "$(code_of "$(cat "$f")")" = "0" ] && OK=$((OK + 1)); done
[ "$OK" = "3" ] && pass "3 个并发参加全部成功" || fail "成功 $OK / 3"

MC=$(member_count)
[ "$MC" = "4" ] && pass "成员数 = 4（发起人 1 + 参加 3）" || fail "成员数 = $MC，期望 4"

echo
echo "[4] u1 再并发参加 5 次 → 期望幂等，人数不变"
rm -f /tmp/vgb_d_*.out
for i in 1 2 3 4 5; do api "$T1" POST "/app/post/$PID/join" >"/tmp/vgb_d_$i.out" & done
wait
MC2=$(member_count)
[ "$MC2" = "4" ] && pass "重复参加 5 次后仍为 4（幂等）" || fail "变成 $MC2，期望 4 → 人数被多加"

echo
echo "[5] u4 参加 → 正好满员（5/5）"
api "$T4" POST "/app/post/$PID/join" >/dev/null
MC3=$(member_count)
[ "$MC3" = "5" ] && pass "成员数 = 5，已满员" || fail "成员数 = $MC3，期望 5"
D=$(api "$T0" GET "/app/post/$PID")
ST=$(echo "$D" | grep -o '"statusText":"[^"]*"' | head -1 | cut -d'"' -f4)
info "满员后帖子状态：$ST"

echo
echo "[6] u5 参加 → 期望 1070 已满员（不是 1073 已结束）"
R5=$(api "$T5" POST "/app/post/$PID/join")
C5=$(code_of "$R5")
if [ "$C5" = "1070" ]; then
  pass "返回 1070 已满员，语义正确"
else
  fail "期望 1070，实际 $C5"
  info "$(echo "$R5" | head -c 120)"
fi

echo
echo "[7] u2 并发退出 3 次 → 期望只减 1 次"
rm -f /tmp/vgb_l_*.out
for i in 1 2 3; do api "$T2" POST "/app/post/$PID/leave" >"/tmp/vgb_l_$i.out" & done
wait
MC4=$(member_count)
[ "$MC4" = "4" ] && pass "退出后成员数 = 4（只减一次，条件更新生效）" || fail "成员数 = $MC4，期望 4"

echo
echo "[8] 退出后是否重新开放"
D2=$(api "$T0" GET "/app/post/$PID")
ST2=$(echo "$D2" | grep -o '"statusText":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$ST2" = "展示中" ]; then
  pass "帖子已重新开放（$ST2）"
else
  fail "帖子状态 = $ST2，期望「展示中」"
fi

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
rm -f /tmp/vgb_*.out
[ "$FAIL" = "0" ] && exit 0 || exit 1
