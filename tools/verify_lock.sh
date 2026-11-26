#!/usr/bin/env bash
# ============================================================
# 分布式锁「真的在工作」的反向验证
#
# 为什么需要这个脚本：
#   功能测试（verify_groupbuy.sh）证明不了锁起作用 ——
#   因为即使锁完全失效，数据库的 CAS 条件更新也会兜底，测试照样全绿。
#   要证明"代码确实走了这把锁"，必须做反向对照：
#     手工在 Redis 里占住 lock:groupbuy:join:{postId}，
#     然后调参加接口 —— 如果被挡住，说明它真的在抢这把锁。
#
# 前提：隧道 + 服务已启动
# 用法：bash tools/verify_lock.sh
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

# 直接操作 Redis（模拟"另一个实例正持着这把锁"）
redis_do() {
  python - "$1" "$2" <<'PY'
import re, sys
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
cmd, key = sys.argv[1], sys.argv[2]
if cmd == 'hold':
    # Redisson 的 RLock 底层是一个 Hash：key=锁名，field=持有者，value=重入次数。
    # 手工塞一个 field，Redisson 就会认为"这把锁被别人占着"。
    r.hset(key, 'manual-test-holder', 1)
    print(f'  已占住锁 {key}（模拟另一台服务器正在执行）')
elif cmd == 'release':
    r.delete(key)
    print(f'  已释放锁 {key}')
elif cmd == 'exists':
    print('held' if r.exists(key) else 'free')
PY
}

echo "=============================================="
echo " 分布式锁反向验证 → $BASE"
echo "=============================================="

echo
echo "[0] 登录 3 个账号"
T_OWNER=$(login eo_owner); T1=$(login eo_u1); T2=$(login eo_u2)
if [ -z "$T_OWNER" ] || [ -z "$T2" ]; then
  fail "登录失败，服务没起？"
  exit 1
fi
pass "token 就绪"

echo
echo "[1] 发一个拼单帖（目标 5 人，不会满员，方便反复参加/退出）"
DEADLINE=$(date -d '+1 day' '+%Y-%m-%d %H:%M:%S')
CREATED=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"GROUPBUY\",\"categoryId\":1,\"title\":\"分布式锁验证单\",\"content\":\"验证锁是否生效\",\"price\":6,\"targetCount\":5,\"groupbuyDeadline\":\"$DEADLINE\",\"pickupPoint\":\"三饭\"}")
PID=$(num_of "$CREATED" id)
[ "$(code_of "$CREATED")" = "0" ] && pass "帖子 postId=$PID" || { fail "创建失败"; exit 1; }
python tools/_publish_post.py "$PID" >/dev/null 2>&1

LOCK_KEY="lock:groupbuy:join:$PID"
echo
info "本次验证的锁 key：$LOCK_KEY"

echo
echo "[2] 场景A｜锁空闲时，参加应该成功（对照组）"
R=$(api "$T1" POST "/app/post/$PID/join")
if [ "$(code_of "$R")" = "0" ]; then
  pass "锁空闲 → 参加成功（说明正常路径没被锁挡住）"
else
  fail "锁空闲却失败：$(code_of "$R")"
fi

echo
echo "[3] ★场景B｜手工占住锁（模拟另一台服务器正在执行），再参加 → 应被挡住"
redis_do hold "$LOCK_KEY" | sed 's/^/  /'
HELD=$(redis_do exists "$LOCK_KEY")
info "Redis 里锁状态：$HELD"
# 接口的 waitTime 是 3 秒，所以这个请求会等约 3 秒后返回"操作太频繁"
R=$(api "$T2" POST "/app/post/$PID/join")
C=$(code_of "$R")
if [ "$C" = "1098" ]; then
  pass "锁被占用 → 请求被挡住（1098 这一单正忙），证明代码真的在抢这把锁"
else
  fail "锁被占用却返回 $C —— 说明接口没有走分布式锁！"
  info "$(echo "$R" | head -c 120)"
fi

echo
echo "[4] ★场景C｜释放锁后再参加 → 应恢复成功（证明锁会正常释放）"
redis_do release "$LOCK_KEY" | sed 's/^/  /'
R=$(api "$T2" POST "/app/post/$PID/join")
if [ "$(code_of "$R")" = "0" ]; then
  pass "锁释放 → 参加恢复成功（没有死锁残留）"
else
  fail "锁释放后仍失败：$(code_of "$R")"
fi

echo
echo "[5] 锁的粒度检查：不同帖子的锁互不影响"
CREATED2=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"GROUPBUY\",\"categoryId\":1,\"title\":\"分布式锁验证单B\",\"content\":\"验证锁粒度\",\"price\":6,\"targetCount\":5,\"groupbuyDeadline\":\"$DEADLINE\",\"pickupPoint\":\"四饭\"}")
PID2=$(num_of "$CREATED2" id)
python tools/_publish_post.py "$PID2" >/dev/null 2>&1
LOCK_KEY2="lock:groupbuy:join:$PID2"
# 占住 A 单的锁
redis_do hold "$LOCK_KEY" > /dev/null
# 参加 B 单：应该不受 A 单的锁影响，立刻成功
R=$(api "$T1" POST "/app/post/$PID2/join")
if [ "$(code_of "$R")" = "0" ]; then
  pass "A 单的锁被占用时，B 单仍能正常参加（锁按 postId 分段，不是全局锁）"
else
  fail "不同单互相阻塞了：$(code_of "$R")（锁粒度写错了）"
fi
redis_do release "$LOCK_KEY" > /dev/null

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
