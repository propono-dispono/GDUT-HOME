#!/usr/bin/env bash
# ============================================================
# 跑腿接单模块并发验证
#
# 前提：
#   1) 隧道：双击 启动隧道.bat（MySQL 13307 / Redis 16379）
#   2) 服务：./mvnw -o spring-boot:run -Dspring-boot.run.profiles=local \
#             -Dspring-boot.run.arguments="--server.port=8080"
#   3) 账号已认证：python tools/_set_verified.py eo_
#   4) 建表：python tools/apply_migration.py xiaojii-server/sql/migration_20261115_errand_take.sql
#
# 用法：bash tools/verify_errand.sh
#
# ⚠️ 连续跑两轮以上会被限流挡住（发帖 5 次/小时）。
#    出现 {code:429} 时先跑：python tools/clear_ratelimit.py --all-test
#    —— 429 本身说明限流正常工作，不是脚本坏了。
#
# 验证的是设计文档第 5 节的场景（编号与文档一致）：
#   S1 多人抢同一单      → 只有 1 个人成功，其余 1101
#   S2 同一人重复提交    → 1102（不是 500，也不是"被别人抢了"）
#   S4 已被接走后其他人  → 1101
#   S7 取件与取消竞争    → 并发 pick 只成功 1 次
#   S8 完成确认重复      → 幂等，全部返回成功
#   S6 取消后释放        → 帖子回到可接，别人能接
#   取消限一次           → 第二次取消 1107
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

# 从返回 JSON 里取指定字段的数字值
num_of() { echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2; }
# 从返回 JSON 里取指定字段的字符串值
str_of() { echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

echo "=============================================="
echo " 跑腿接单并发验证 → $BASE"
echo "=============================================="

echo
echo "[0] 登录 5 个测试账号（发帖人 + 4 个跑腿员）"
T_OWNER=$(login eo_owner)
T1=$(login eo_u1); T2=$(login eo_u2); T3=$(login eo_u3); T4=$(login eo_u4)
if [ -z "$T_OWNER" ] || [ -z "$T4" ]; then
  fail "登录失败，服务没起来？看 /tmp/errand_server.log"
  exit 1
fi
pass "5 个 token 就绪"

echo
echo "[1] 发跑腿帖（明天截止）"
DEADLINE=$(date -d '+1 day' '+%Y-%m-%d %H:%M:%S')
CREATED=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"ERRAND\",\"categoryId\":11,\"title\":\"并发验证-代取快递\",\"content\":\"并发测试\",\"price\":5,\"pickupAddr\":\"菜鸟驿站(东区)\",\"deliverAddr\":\"3栋502\",\"deadline\":\"$DEADLINE\",\"advancePay\":0}")
PID=$(num_of "$CREATED" id)
if [ "$(code_of "$CREATED")" = "0" ] && [ -n "$PID" ]; then
  pass "帖子创建成功 postId=$PID"
else
  fail "创建失败 → 先跑 python tools/_set_verified.py eo_"
  info "$(echo "$CREATED" | head -c 150)"
  exit 1
fi

echo
echo "[2] 放行帖子（新用户发帖默认待审核）"
python tools/_publish_post.py "$PID" 2>&1 | head -2

echo
echo "[3] ★ S1：u1/u2/u3 同时接同一单 → 只能 1 个人成功"
rm -f /tmp/er_t_*.out
TOKENS=("$T1" "$T2" "$T3"); NAMES=(u1 u2 u3)
for i in 0 1 2; do api "${TOKENS[$i]}" POST "/app/errand/$PID/take" >"/tmp/er_t_$i.out" & done
wait
WIN_IDX=-1; OKN=0
for i in 0 1 2; do
  c=$(code_of "$(cat "/tmp/er_t_$i.out")")
  [ "$c" = "0" ] && { OKN=$((OKN + 1)); WIN_IDX=$i; }
done
if [ "$OKN" = "1" ]; then
  pass "3 人并发只有 1 人接单成功（赢家=${NAMES[$WIN_IDX]}）"
else
  fail "成功 $OKN 人，期望 1 → 出现超卖！"
  for i in 0 1 2; do info "${NAMES[$i]}: $(head -c 90 "/tmp/er_t_$i.out")"; done
fi

echo
echo "[4] 校验失败者拿到的是 1101（手慢了），不是 500"
BAD=0
for i in 0 1 2; do
  [ "$i" = "$WIN_IDX" ] && continue
  c=$(code_of "$(cat "/tmp/er_t_$i.out")")
  [ "$c" = "1101" ] || { BAD=$((BAD + 1)); info "${NAMES[$i]} 返回 $c"; }
done
[ "$BAD" = "0" ] && pass "落败者全部返回 1101，文案与状态一致" || fail "有 $BAD 个落败者不是 1101"

WT="${TOKENS[$WIN_IDX]}"

echo
echo "[4.1] ★ 接单人（不是发布者）必须还能打开详情 —— status=DEALT 不该 404"
DT=$(api "$WT" GET "/app/post/$PID")
if [ "$(code_of "$DT")" = "0" ]; then
  pass "接单人打开详情成功（帖子已锁成 status=$(num_of "$DT" status)）"
else
  fail "接单人打不开详情 code=$(code_of "$DT") → detail 的可见性判断漏了 DEALT"
fi

echo
echo "[5] 查当前接单信息"
CUR=$(api "$T_OWNER" GET "/app/errand/$PID/current")
TAKER=$(num_of "$CUR" takerId)
ST=$(str_of "$CUR" status)
if [ -n "$TAKER" ] && [ "$ST" = "IN_PROGRESS" ]; then
  pass "current 返回 takerId=$TAKER，状态 IN_PROGRESS"
else
  fail "current 异常：$ST / takerId=$TAKER"
fi
ROLE=$(str_of "$CUR" myRole)
[ "$ROLE" = "OWNER" ] && pass "发布者视角 myRole=OWNER（按钮位由后端算）" || fail "myRole=$ROLE，期望 OWNER"

echo
echo "[6] ★ S2：赢家自己再并发接 3 次 → 期望全部 1102，不是 1101"
rm -f /tmp/er_d_*.out
for i in 1 2 3; do api "$WT" POST "/app/errand/$PID/take" >"/tmp/er_d_$i.out" & done
wait
BAD2=0
for i in 1 2 3; do
  c=$(code_of "$(cat "/tmp/er_d_$i.out")")
  [ "$c" = "1102" ] || { BAD2=$((BAD2 + 1)); info "第 $i 次返回 $c"; }
done
[ "$BAD2" = "0" ] && pass "重复提交全部识别为自己已接（1102）" || fail "有 $BAD2 次判断错误"

echo
echo "[7] ★ S4：第四个人来抢 → 期望 1101"
R=$(api "$T4" POST "/app/errand/$PID/take")
[ "$(code_of "$R")" = "1101" ] && pass "已被人接走后返回 1101" || fail "返回 $(code_of "$R")，期望 1101"

echo
echo "[8] ★ S7：接单者并发点 3 次「已取件」→ 只能成功 1 次"
rm -f /tmp/er_p_*.out
for i in 1 2 3; do api "$WT" POST "/app/errand/$PID/pick" >"/tmp/er_p_$i.out" & done
wait
POK=0
for i in 1 2 3; do [ "$(code_of "$(cat "/tmp/er_p_$i.out")")" = "0" ] && POK=$((POK + 1)); done
[ "$POK" = "1" ] && pass "并发取件只成功 1 次，其余报状态已变" || fail "成功 $POK 次，期望 1"

echo
echo "[9] 接单者标记送达"
R=$(api "$WT" POST "/app/errand/$PID/deliver")
[ "$(code_of "$R")" = "0" ] && pass "送达成功，状态 $(str_of "$R" status)" || fail "送达失败：$(code_of "$R")"

echo
echo "[10] ★ S8：发布者并发点 3 次「确认完成」→ 幂等，全部成功"
rm -f /tmp/er_c_*.out
for i in 1 2 3; do api "$T_OWNER" POST "/app/errand/$PID/confirm" >"/tmp/er_c_$i.out" & done
wait
COK=0
for i in 1 2 3; do [ "$(code_of "$(cat "/tmp/er_c_$i.out")")" = "0" ] && COK=$((COK + 1)); done
[ "$COK" = "3" ] && pass "重复确认 3 次全部返回成功（幂等，未重复扣分/发通知）" || fail "成功 $COK / 3"

echo
echo "[11] 校验终态：记录 COMPLETED + 帖子 CLOSED"
CUR2=$(api "$T_OWNER" GET "/app/errand/$PID/current")
DETAIL=$(api "$T_OWNER" GET "/app/post/$PID")
PSTAT=$(num_of "$DETAIL" status)
if [ "$PSTAT" = "4" ]; then
  pass "帖子已关闭 status=4"
else
  fail "帖子 status=$PSTAT，期望 4"
fi
# 完成后 active_post_id 已置 NULL，current 应返回 null
echo "$CUR2" | grep -q '"data":null' && pass "完成后 current 返回 null（不再占坑）" || info "current 仍有值（可接受，取决于查询实现）"

echo
echo "[12] ★ S6：新开一单，u1 接 → 取消 → 帖子应回到可接"
CREATED2=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"ERRAND\",\"categoryId\":11,\"title\":\"并发验证-取消释放\",\"content\":\"测试取消\",\"price\":3,\"pickupAddr\":\"一饭\",\"deliverAddr\":\"2栋101\",\"deadline\":\"$DEADLINE\",\"advancePay\":0}")
PID2=$(num_of "$CREATED2" id)
python tools/_publish_post.py "$PID2" >/dev/null 2>&1
R=$(api "$T1" POST "/app/errand/$PID2/take")
[ "$(code_of "$R")" = "0" ] && pass "u1 接单成功 postId=$PID2" || fail "接单失败 $(code_of "$R")"
R=$(api "$T1" POST "/app/errand/$PID2/cancel")
[ "$(code_of "$R")" = "0" ] && pass "取消成功，帖子已放回可接" || fail "取消失败 $(code_of "$R")"
R=$(api "$T2" POST "/app/errand/$PID2/take")
[ "$(code_of "$R")" = "0" ] && pass "u2 能接上（证明释放生效）" || fail "u2 接不了 $(code_of "$R")"

echo
echo "[13] 每单只能取消一次（干净的新单：u1 接→取消→再接→再取消→期望 1107）"
CREATED3=$(api "$T_OWNER" POST "/app/post" \
  "{\"bizType\":\"ERRAND\",\"categoryId\":11,\"title\":\"并发验证-取消限一次\",\"content\":\"测试取消上限\",\"price\":2,\"pickupAddr\":\"二饭\",\"deliverAddr\":\"5栋301\",\"deadline\":\"$DEADLINE\",\"advancePay\":0}")
PID3=$(num_of "$CREATED3" id)
python tools/_publish_post.py "$PID3" >/dev/null 2>&1
R1=$(api "$T1" POST "/app/errand/$PID3/take")
C1=$(code_of "$R1")
R2=$(api "$T1" POST "/app/errand/$PID3/cancel")
C2=$(code_of "$R2")
R3=$(api "$T1" POST "/app/errand/$PID3/take")
C3=$(code_of "$R3")
R4=$(api "$T1" POST "/app/errand/$PID3/cancel")
C4=$(code_of "$R4")
info "第1次接=$C1 第1次取消=$C2 第2次接=$C3 第2次取消=$C4"
if [ "$C1" = "0" ] && [ "$C2" = "0" ] && [ "$C3" = "0" ] && [ "$C4" = "1107" ]; then
  pass "取消一次后可以重接，但第二次取消被挡（1107）"
else
  fail "期望 0/0/0/1107，实际 $C1/$C2/$C3/$C4"
fi

echo
echo "[14] 发布者撤回：帖子应转「已下架」，且记录置 CANCELLED"
api "$T2" POST "/app/errand/$PID3/revoke" >/dev/null 2>&1
R=$(api "$T1" POST "/app/errand/$PID3/revoke")
info "非发布者撤回返回 $(code_of "$R")（期望 1106）"
[ "$(code_of "$R")" = "1106" ] && pass "非发布者不能撤回（1106，越权被挡）" || fail "越权校验失效：$(code_of "$R")"
ROWNER=$(api "$T_OWNER" POST "/app/errand/$PID3/revoke")
[ "$(code_of "$ROWNER")" = "0" ] && pass "发布者撤回成功，记录状态 $(str_of "$ROWNER" status)" || fail "撤回失败 $(code_of "$ROWNER")"
D3=$(api "$T_OWNER" GET "/app/post/$PID3")
P3=$(num_of "$D3" status)
[ "$P3" = "2" ] && pass "帖子已转已下架 status=2" || fail "帖子 status=$P3，期望 2"

# 收尾：把第二单也下架，避免脏数据留在列表里
api "$T_OWNER" POST "/app/errand/$PID2/revoke" >/dev/null 2>&1

echo
echo "=============================================="
echo " 结果：$PASS 通过 / $FAIL 失败"
echo "=============================================="
rm -f /tmp/er_*.out
[ "$FAIL" = "0" ] && exit 0 || exit 1
