"""查看 Redis Stream 消息队列的实时状态。

【一句话】把"队列里还有多少条、消费组落后多少、有没有卡住的消息、死信有几条"一次打出来。

为什么需要它：
  消息队列最怕的是"看起来正常，其实消息堆着没人消费"。
  光看业务接口返回成功，是看不出这种情况的 —— 必须直接看队列本身。

用法：
    python tools/mq_status.py            # 看 notice 主题
    python tools/mq_status.py notice     # 指定主题

判读方法：
    length        队列里的消息条数（含已被消费但还没被裁剪的）
    last-delivered 消费组已经投递到的位置
    pending       已投递但还没 ACK 的条数 —— 这个数持续变大 = 消费者出问题了
    lag           （本项目不打印，可用 XINFO 看）还有多少条没被投递
    dlq           死信条数 —— 大于 0 说明有消息重试 3 次仍失败，要人工看
"""
import re
import sys
from pathlib import Path

import redis

ROOT = Path(__file__).resolve().parent.parent
LOCAL_YML = ROOT / "xiaojii-server" / "src" / "main" / "resources" / "application-local.yml"


def redis_client() -> redis.Redis:
    """从 application-local.yml 读连接信息（不把密码写进命令行）。"""
    text = LOCAL_YML.read_text(encoding="utf-8")
    part = text.split("redis:")[1]
    host = re.search(r"host:\s*(\S+)", part).group(1)
    port = int(re.search(r"port:\s*(\d+)", part).group(1))
    pwd = re.search(r"password:\s*(\S+)", part).group(1)
    db = int(re.search(r"database:\s*(\d+)", part).group(1))
    return redis.Redis(host=host, port=port, password=pwd, db=db, decode_responses=True)


def show(r: redis.Redis, topic: str) -> None:
    key = f"mq:{topic}"
    dlq = key + ":dlq"

    if not r.exists(key):
        print(f"[{topic}] stream 不存在（还没发过消息）")
        return

    print(f"===== mq:{topic} =====")
    print(f"length   : {r.xlen(key)}")

    try:
        groups = r.xinfo_groups(key)
    except redis.ResponseError:
        groups = []

    if not groups:
        print("消费组   : 无（没有消费者订阅，或服务没启动）")
    for g in groups:
        print(f"消费组   : {g['name']}")
        print(f"  last-delivered : {g['last-delivered-id']}")
        print(f"  pending        : {g['pending']}   <- 持续变大说明消费者卡住了")
        print(f"  consumers      : {g['consumers']}")

    # 死信
    if r.exists(dlq):
        print(f"死信条数 : {r.xlen(dlq)}   <- 大于 0 要人工看内容")
        for item in r.xrange(dlq, count=3):
            fields = item[1]
            print(f"  - id={item[0]} error={fields.get('error')} payload={str(fields.get('payload'))[:80]}")
    else:
        print("死信条数 : 0 ✅")

    # 最近 3 条消息，方便确认"到底发出去没有"
    print("最近消息 :")
    for item in r.xrevrange(key, count=3):
        fields = item[1]
        payload = str(fields.get("payload", ""))
        print(f"  - {item[0]}  {payload[:100]}")


def main() -> None:
    topic = sys.argv[1] if len(sys.argv) > 1 else "notice"
    show(redis_client(), topic)


if __name__ == "__main__":
    main()
