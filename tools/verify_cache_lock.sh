#!/usr/bin/env bash
# ============================================================
# 教务「缓存击穿」分布式锁 —— 反向验证
#
# 为什么需要反向验证：
#   正常功能测试证明不了锁在工作。这里的做法是手工在 Redis 里占住锁，
#   看接口是不是真的被挡住（返回"课表正在加载"而不是"会话已失效"）。
#
# ⚠️ 前提：服务必须**关闭 fixture 模式**启动，否则请求走样例数据，根本不进 realTimetable：
#     ./mvnw spring-boot:run -Dspring-boot.run.profiles=local \
#       -Dspring-boot.run.arguments="--server.port=8080 --edu.fixture-dir="
#
# 用法：bash tools/verify_cache_lock.sh
# ============================================================

BASE=${BASE:-http://127.0.0.1:8080/api}
# 当前学期编码：2026-2027 第一学期 → "2026" + "01"
# termCodeOf() 取学年前 4 位 + 学期号，跨学期要改这里
TERM=202601
PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  [PASS] $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  [FAIL] $1"; }
info() { echo "  [INFO] $1"; }

code_of() { echo "$1" | grep -o '"code":[0-9-]*' | head -1 | cut -d: -f2; }
msg_of() { echo "$1" | grep -o '"msg":"[^"]*"' | head -1 | cut -d'"' -f4; }

login() {
  curl -s --noproxy '*' -X POST "$BASE/app/auth/dev-login" \
    -H 'Content-Type: application/json' \
    -d "{\"openid\":\"$1\",\"nickname\":\"$1\"}" \
  | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4
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

# 直接操作 Redis 里的锁 key（模拟"另一个线程/另一台服务器正在抓教务"）
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
    r.hset(key, 'manual-holder', 1)
    r.expire(key, 60)
    print(f'  已占住锁 {key}（模拟另一个线程正在抓教务）')
elif cmd == 'release':
    r.delete(key)
    print(f'  已释放锁 {key}')
PY
}

echo "=============================================="
echo " 教务缓存击穿锁 → $BASE"
echo "=============================================="

echo
echo "[0] 准备账号"
T1=$(login eo_u1); T2=$(login eo_u2)
UID1=$(get_uid eo_u1); UID2=$(get_uid eo_u2)
if [ -z "$T1" ] || [ -z "$UID1" ]; then fail "登录失败"; exit 1; fi
pass "eo_u1 id=$UID1 / eo_u2 id=$UID2"
LOCK_KEY="lock:edu:timetable:$UID1:$TERM"
info "本次验证的锁 key：$LOCK_KEY"

echo
echo "[1] 场景A｜锁空闲：请求会一路走到'去教务取课表'（对照组）"
R=$(curl -s --noproxy '*' "$BASE/app/edu/timetable" -H "Authorization: Bearer $T1")
C=$(code_of "$R"); M=$(msg_of "$R")
info "返回 code=$C msg=$M"
if [ "$M" = "课表正在加载，请稍后重试" ]; then
  fail "锁空闲却返回'正在加载' —— 说明有残留锁，先清 $LOCK_KEY"
else
  pass "锁空闲时走到了真实取数分支（本次无教务会话，所以报会话失效是预期的）"
fi

echo
echo "[2] ★场景B｜手工占住锁（模拟另一个线程正在抓教务），再请求 → 应被挡在门外"
redis_do hold "$LOCK_KEY" | sed 's/^/  /'
R=$(curl -s --noproxy '*' "$BASE/app/edu/timetable" -H "Authorization: Bearer $T1")
C=$(code_of "$R"); M=$(msg_of "$R")
if [ "$M" = "课表正在加载，请稍后重试" ]; then
  pass "锁被占用 → 返回「课表正在加载，请稍后重试」，证明请求真的走了这把锁"
else
  fail "锁被占用却返回 code=$C msg=$M —— 说明拦截请求走的不是这把锁"
fi

echo
echo "[3] ★场景C｜释放锁后再请求 → 应恢复正常流程（证明不会死锁残留）"
redis_do release "$LOCK_KEY" | sed 's/^/  /'
R=$(curl -s --noproxy '*' "$BASE/app/edu/timetable" -H "Authorization: Bearer $T1")
C=$(code_of "$R"); M=$(msg_of "$R")
if [ "$M" = "课表正在加载，请稍后重试" ]; then
  fail "锁已释放却仍返回'正在加载' —— 锁泄漏了"
else
  pass "锁释放后恢复正常流程（code=$C）"
fi

echo
echo "[4] ★场景D｜锁粒度：占住 u1 的锁，u2 查询不受影响"
redis_do hold "$LOCK_KEY" | sed 's/^/  /'
R2=$(curl -s --noproxy '*' "$BASE/app/edu/timetable" -H "Authorization: Bearer $T2")
M2=$(msg_of "$R2")
if [ "$M2" = "课表正在加载，请稍后重试" ]; then
  fail "u1 的锁挡住了 u2 —— 锁粒度写错了（可能是全局锁）"
else
  pass "u1 的锁不影响 u2（锁 key 按 userId + 学期分段）"
fi
redis_do release "$LOCK_KEY" > /dev/null

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
[ "$FAIL" = "0" ] && exit 0 || exit 1
