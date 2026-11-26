"""清掉指定用户的限流计数（rate:user:{userId}:*）。

【一句话】验证脚本连续跑几轮后，发帖会被 @RateLimit 挡住（429），
这个工具把测试账号的限流 key 删掉，让验证能继续跑。

为什么需要它：
  发帖接口限制 5 次/小时（RateLimitAspect + ZSET 滑动窗口）。
  verify_errand.sh 每轮要发 3 个帖子，跑两轮就超了。
  429 说明限流是好的，但会挡住并发验证本身。

用法：
    python tools/clear_ratelimit.py 15 16 17 18 19     # 按 user_id
    python tools/clear_ratelimit.py --all-test         # 清 eo_ 和 vgb_ 开头账号

⚠️ 只删 rate: 前缀的 key，不碰别的（尤其不碰 token 白名单）。
"""
import re
import sys
from pathlib import Path

import pymysql
import redis

ROOT = Path(__file__).resolve().parent.parent
LOCAL_YML = ROOT / "xiaojii-server" / "src" / "main" / "resources" / "application-local.yml"


def load_local_conf():
    """从 application-local.yml 里取 MySQL 密码和 Redis 连接信息，避免写死在命令行。"""
    text = LOCAL_YML.read_text(encoding="utf-8")
    db_pwd = re.search(r"password:\s*(\S+)", text.split("datasource:")[1]).group(1)
    redis_part = text.split("redis:")[1]
    host = re.search(r"host:\s*(\S+)", redis_part).group(1)
    port = int(re.search(r"port:\s*(\d+)", redis_part).group(1))
    pwd = re.search(r"password:\s*(\S+)", redis_part).group(1)
    db = int(re.search(r"database:\s*(\d+)", redis_part).group(1))
    return db_pwd, host, port, pwd, db


def user_ids_from_db(db_pwd: str, prefixes) -> list:
    """按 openid 前缀查出测试账号的 id。"""
    conn = pymysql.connect(host="127.0.0.1", port=13307, user="root",
                           password=db_pwd, database="xiaojii", charset="utf8mb4")
    try:
        cur = conn.cursor()
        ids = []
        for p in prefixes:
            cur.execute("SELECT id FROM user WHERE openid LIKE %s", (p + "%",))
            ids.extend(r[0] for r in cur.fetchall())
        return sorted(set(ids))
    finally:
        conn.close()


def main() -> None:
    db_pwd, host, port, pwd, db = load_local_conf()

    if "--all-test" in sys.argv:
        ids = user_ids_from_db(db_pwd, ["eo_", "vgb_"])
    else:
        ids = [int(x) for x in sys.argv[1:] if x.isdigit()]

    if not ids:
        raise SystemExit("用法：python tools/clear_ratelimit.py 15 16 17 或 --all-test")

    r = redis.Redis(host=host, port=port, password=pwd, db=db, decode_responses=True)
    removed = 0
    for uid in ids:
        for key in r.scan_iter(match=f"rate:user:{uid}:*"):
            r.delete(key)
            removed += 1
            print(f"  删除 {key}")
    print(f"\n完成：{len(ids)} 个用户，删除 {removed} 个限流 key")
    print("（只删 rate: 前缀，token 白名单和其他 key 没动）")


if __name__ == "__main__":
    main()
