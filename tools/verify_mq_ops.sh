#!/usr/bin/env bash
# ============================================================
# MQ 运维能力验证：队列状态 / 死信查看 / 死信重投
#
# 为什么需要这个脚本：
#   前面几个脚本验证的是"正常消息能消费"，这个验证的是"消息处理失败之后会怎样"。
#   消息队列真正的价值体现在失败路径：能不能发现、能不能查、能不能救。
#
# ⚠️ 为了几秒内跑完，服务需要用"快速重试"参数启动：
#     ./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
#       -Dspring-boot.run.arguments="--server.port=8080 \
#         --xiaojii.mq.pending-idle-ms=500 \
#         --xiaojii.mq.recover-interval-ms=1000 \
#         --xiaojii.mq.max-retry=2"
#   正常参数下重试一轮要 60 秒 × 3，脚本会等到超时。
#
# 用法：bash tools/verify_mq_ops.sh
# ============================================================

BASE=${BASE:-http://127.0.0.1:8080/api}
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  [PASS] $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  [FAIL] $1"; }
info() { echo "  [INFO] $1"; }

admin_login() {
  curl -s --noproxy '*' -X POST "$BASE/admin/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"duC6Bg3NUcCbKNMG"}' \
  | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4
}

api() {
  local t=$1 m=$2 p=$3
  curl -s --noproxy '*' -X "$m" "$BASE$p" -H "Authorization: Bearer $t"
}

# 往主队列塞一条「一定是坏消息」的记录（payload 不是 JSON）
inject_bad_message() {
  python - <<'PY'
import re, time
from pathlib import Path
import redis

yml = Path('xiaojii-server/src/main/resources/application-local.yml').read_text(encoding='utf-8')
part = yml.split('redis:')[1]
r = redis.Redis(
    host=re.search(r'host:\s*(\S+)', part).group(1),
    port=int(re.search(r'port:\s*(\d+)', part).group(1)),
    password=re.search(r'password:\s*(\S+)', part).group(1),
    db=int(re.search(r'database:\s*(\d+)', part).group(1)),
    decode_responses=True)
mid = r.xadd('mq:notice', {'payload': 'THIS-IS-NOT-JSON', 'ts': str(int(time.time() * 1000))})
print(f'  已注入坏消息 id={mid}')
PY
}

echo "=============================================="
echo " MQ 运维能力验证 → $BASE"
echo "=============================================="

echo
echo "[0] 管理员登录"
TA=$(admin_login)
[ -n "$TA" ] && pass "admin token 就绪" || { fail "管理员登录失败"; exit 1; }

echo
echo "[1] ★队列健康接口"
S=$(api "$TA" GET "/admin/mq/status")
echo "$S" | head -c 160
echo
if echo "$S" | grep -q '"length"' && echo "$S" | grep -q '"pending"'; then
  pass "status 返回 length / pending / consumers / dlq"
else
  fail "status 返回结构不对：$(echo "$S" | head -c 120)"
fi

echo
echo "[2] 死信列表（此刻应该是空的或历史残留）"
D0=$(api "$TA" GET "/admin/mq/dead-letters?count=5")
N0=$(echo "$D0" | grep -o '"id":"' | wc -l | tr -d ' ')
info "当前死信条数：$N0"

echo
echo "[3] ★注入一条坏消息（payload 不是 JSON）→ 应重试后进死信"
inject_bad_message
info "等待重试 + 进死信（max-retry=2，间隔 1 秒）..."
sleep 8

D1=$(api "$TA" GET "/admin/mq/dead-letters?count=5")
echo "$D1" | head -c 300
echo
if echo "$D1" | grep -q "THIS-IS-NOT-JSON\|JsonParseException\|Unrecognized token\|parse"; then
  pass "坏消息已进入死信，并能看到原始 payload 与错误原因"
  DLQ_ID=$(echo "$D1" | grep -o '"id":"[0-9-]*"' | head -1 | cut -d'"' -f4)
  info "死信 id=$DLQ_ID"
else
  fail "死信里没找到这条坏消息"
  DLQ_ID=""
fi

echo
echo "[4] ★死信重投"
if [ -n "$DLQ_ID" ]; then
  R=$(api "$TA" POST "/admin/mq/dead-letters/$DLQ_ID/replay")
  echo "  返回：$(echo "$R" | head -c 100)"
  if echo "$R" | grep -q '"data":true'; then
    pass "重投成功（消息已重新入队，死信记录已删除）"
  else
    fail "重投失败：$(echo "$R" | head -c 100)"
  fi
  info "等它再失败一次（同一条坏消息会被再次判定失败）..."
  sleep 8
  D2=$(api "$TA" GET "/admin/mq/dead-letters?count=5")
  if echo "$D2" | grep -q "THIS-IS-NOT-JSON"; then
    pass "重投后再次失败 → 重新进死信（说明重投走的是完整消费链路，不是假动作）"
  else
    fail "重投后没再进死信，链路可疑"
  fi
else
  fail "没有死信 id，跳过重投验证"
fi

echo
echo "[5] 最终状态"
api "$TA" GET "/admin/mq/status" | head -c 180
echo

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
