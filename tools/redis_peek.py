#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · Redis 查看工具（无需安装 redis-cli / 无需配 IDEA 数据源）

用法：
    python redis_peek.py                 列出所有 key（按前缀分组）
    python redis_peek.py --full          同时打印每个 key 的值和剩余过期时间
    python redis_peek.py --pattern "user:*"   只看某类 key
    python redis_peek.py --del "user:status:3"  删掉某个 key（危险，会二次确认）
"""

import argparse
import os
import socket
import sys

HOST = "127.0.0.1"
PORT = 16379          # 隧道端口（服务器真实端口是 6379）
USER = "default"      # Redis 6+ 的默认 ACL 用户；这个实例没有 root 用户
# 密码从环境变量读，不写进仓库（本地跑之前先 export REDIS_PASSWORD=...）
PASSWORD = os.environ.get("REDIS_PASSWORD", "")
DB = 0


class Redis:
    """极简 RESP 客户端：只实现本工具用到的几个命令。"""

    def __init__(self, host, port, user, password, db):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.buf = b""
        if user:
            self.cmd("AUTH", user, password)
        else:
            self.cmd("AUTH", password)
        self.cmd("SELECT", str(db))

    def _readline(self):
        while b"\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("连接被关闭")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _read(self):
        line = self._readline()
        tag, rest = line[:1], line[1:]
        if tag == b"+":
            return rest.decode("utf-8", "replace")
        if tag == b"-":
            raise RuntimeError(rest.decode("utf-8", "replace"))
        if tag == b":":
            return int(rest)
        if tag == b"$":
            n = int(rest)
            if n == -1:
                return None
            while len(self.buf) < n + 2:
                self.buf += self.sock.recv(4096)
            data, self.buf = self.buf[:n], self.buf[n + 2:]
            return data.decode("utf-8", "replace")
        if tag == b"*":
            n = int(rest)
            if n == -1:
                return None
            return [self._read() for _ in range(n)]
        raise RuntimeError("无法解析的响应: %r" % line[:40])

    def cmd(self, *args):
        parts = [b"*%d\r\n" % len(args)]
        for a in args:
            b = str(a).encode("utf-8")
            parts.append(b"$%d\r\n" % len(b) + b + b"\r\n")
        self.sock.sendall(b"".join(parts))
        return self._read()

    def scan_all(self, pattern="*"):
        keys, cursor = [], "0"
        while True:
            cursor, batch = self.cmd("SCAN", cursor, "MATCH", pattern, "COUNT", "500")
            keys.extend(batch or [])
            if cursor == "0":
                break
        return keys

    def ttl(self, key):
        return self.cmd("TTL", key)

    def type_of(self, key):
        return self.cmd("TYPE", key)

    def value(self, key):
        t = self.type_of(key)
        if t == "string":
            return self.cmd("GET", key)
        if t == "hash":
            flat = self.cmd("HGETALL", key) or []
            return dict(zip(flat[0::2], flat[1::2]))
        if t == "list":
            return self.cmd("LRANGE", key, "0", "-1")
        if t == "set":
            return self.cmd("SMEMBERS", key)
        if t == "zset":
            flat = self.cmd("ZRANGE", key, "0", "-1", "WITHSCORES") or []
            return dict(zip(flat[0::2], flat[1::2]))
        return "<%s>" % t

    def close(self):
        try:
            self.close_quiet()
        except Exception:
            pass

    def close_quiet(self):
        self.sock.close()


def human_ttl(t):
    if t == -1:
        return "永久"
    if t == -2:
        return "已过期"
    if t >= 3600:
        return "%d小时%d分" % (t // 3600, (t % 3600) // 60)
    if t >= 60:
        return "%d分%d秒" % (t // 60, t % 60)
    return "%d秒" % t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", action="store_true", help="打印值与过期时间")
    ap.add_argument("--pattern", default="*", help="只匹配某类 key，如 user:*")
    ap.add_argument("--del", dest="delete", default=None, help="删除指定 key")
    args = ap.parse_args()

    try:
        r = Redis(HOST, PORT, USER, PASSWORD, DB)
    except ConnectionRefusedError:
        print("[×] 连不上 %s:%d —— 隧道没开。先双击「启动后台.bat」。" % (HOST, PORT))
        return 1
    except Exception as e:
        print("[×] 连接/认证失败：%s" % e)
        return 1

    print("[✓] 已连接 %s:%d (db%d, 用户 %s)\n" % (HOST, PORT, DB, USER))

    if args.delete:
        answer = input("确认删除 key「%s」？输入 yes 继续：" % args.delete)
        if answer.strip().lower() != "yes":
            print("已取消")
            return 0
        n = r.cmd("DEL", args.delete)
        print("已删除 %d 个 key" % n)
        return 0

    keys = r.scan_all(args.pattern)
    if not keys:
        print("（没有匹配的 key）")
        return 0

    groups = {}
    for k in keys:
        prefix = k.split(":")[0] + ":"
        groups.setdefault(prefix, []).append(k)

    print("共 %d 个 key：\n" % len(keys))
    for prefix in sorted(groups):
        items = groups[prefix]
        print("── %-16s %d 个" % (prefix, len(items)))
        show = items if args.full else items[:5]
        for k in show:
            if args.full:
                print("     %-36s 类型=%-6s TTL=%-10s" % (k, r.type_of(k), human_ttl(r.ttl(k))))
                v = r.value(k)
                text = str(v)
                if len(text) > 160:
                    text = text[:160] + "…"
                print("        %s" % text)
            else:
                print("     %s" % k)
        if not args.full and len(items) > 5:
            print("     … 还有 %d 个（加 --full 全看）" % (len(items) - 5))
        print()

    r.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
