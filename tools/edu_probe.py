#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · 广工教务接口探测器（A 方案工具）

背景：课表/成绩/考试三块必须在「真实登录后的会话」下确认请求路径与返回结构。
本工具从服务器 Redis 里取出 `edu:session:{userId}` 中的教务 Cookie，
带 Cookie 去访问教务系统，把候选接口的响应落盘，供后续写解析器。

为什么用 curl 而不是 urllib：
    教务证书链里有 GDCA 中间证书，Python 自带 CA 包不认；
    本机 curl 走 Windows 证书库，能正常握手。生产代码的证书处理在 Java 侧，与本工具无关。

用法：
    python tools/edu_probe.py --discover                 # 无 Cookie，先看哪些 action 真实存在
    python tools/edu_probe.py --sessions                 # 列出 Redis 里的教务会话（脱敏）
    python tools/edu_probe.py --session <userId>         # 带该用户 Cookie 抓所有候选接口
    python tools/edu_probe.py --session <userId> --url "/xsKbcx!toXsKbcx.action"   # 只抓一个

产物：tools/_edu/<slug>.html|json + 控制台摘要表
"""

import argparse
import json
import os
import socket
import subprocess
import sys
import time

BASE = "https://jxfw.gdut.edu.cn"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_edu")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/128.0 Safari/537.36")

# Redis（经 SSH 隧道）
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 16379
# 密码从环境变量读，不写进仓库（本地跑之前先 export REDIS_PASSWORD=...）
REDIS_PASSWORD = os.environ.get("REDIS_PASSWORD", "")

# 候选接口：Struts2 的 `xxx!method.action` 写法，覆盖课表/成绩/考试/学期几类命名习惯
CANDIDATES = [
    ("首页/菜单", "/index!index.action"),
    ("首页/菜单", "/menu!getMenu.action"),
    ("首页/菜单", "/main!index.action"),
    ("学期信息", "/public!getCurrentXnxq.action"),
    ("学期信息", "/common!getXnxq.action"),
    ("学期信息", "/xsxx!getXsxx.action"),
    ("课表", "/xsKbcx!toXsKbcx.action"),
    ("课表", "/xsKbcx!queryXsKbcx.action"),
    ("课表", "/kbcx!toKbcx.action"),
    ("课表", "/kbcx!query.action"),
    ("课表", "/xskb!query.action"),
    ("成绩", "/xsCjcx!toXsCjcx.action"),
    ("成绩", "/xsCjcx!queryXsCjcx.action"),
    ("成绩", "/cjcx!toCjcx.action"),
    ("成绩", "/cjcx!query.action"),
    ("成绩", "/xscj!query.action"),
    ("考试", "/xsKscx!toXsKscx.action"),
    ("考试", "/kscx!toKscx.action"),
    ("考试", "/kscx!query.action"),
    ("考试", "/ksap!query.action"),
]


# ---------------- 极简 Redis 客户端（只做 SELECT/GET/KEYS） ----------------

class Redis:
    def __init__(self, host, port, password, db=0):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.f = self.sock.makefile("rb")
        if password:
            self.cmd("AUTH", password)
        self.cmd("SELECT", str(db))

    def cmd(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            b = str(a).encode("utf-8")
            out += b"$%d\r\n%s\r\n" % (len(b), b)
        self.sock.sendall(out)
        return self._read()

    def _read(self):
        line = self.f.readline().rstrip(b"\r\n")
        if not line:
            return None
        tag, rest = line[:1], line[1:]
        if tag == b"+":
            return rest.decode("utf-8")
        if tag == b"-":
            raise RuntimeError(rest.decode("utf-8"))
        if tag == b":":
            return int(rest)
        if tag == b"$":
            n = int(rest)
            if n < 0:
                return None
            data = self.f.read(n + 2)[:-2]
            return data.decode("utf-8", "replace")
        if tag == b"*":
            n = int(rest)
            return [self._read() for _ in range(n)]
        return None


def redis_conn():
    return Redis(REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, 0)


# ---------------- HTTP（走 curl） ----------------

def fetch(url, cookie=None, timeout=25):
    cmd = ["curl", "-sS", "--max-time", str(timeout), "-A", UA,
           "-o", "-", "-w", "\n__META__%{http_code}|%{size_download}|%{content_type}|%{redirect_url}"]
    if cookie:
        cmd += ["-H", "Cookie: " + cookie, "-H", "Referer: " + BASE + "/login!welcome.action"]
    cmd.append(url if url.startswith("http") else BASE + url)
    r = subprocess.run(cmd, capture_output=True)
    raw = r.stdout
    meta = {"code": "", "size": 0, "type": "", "redirect": ""}
    body = raw
    idx = raw.rfind(b"\n__META__")
    if idx >= 0:
        body = raw[:idx]
        parts = raw[idx + 9:].decode("utf-8", "replace").split("|")
        meta["code"], meta["size"], meta["type"], meta["redirect"] = (
            parts[0], int(parts[1] or 0), parts[2], parts[3])
    return body, meta


def title_of(html):
    low = html.lower()
    i = low.find(b"<title>")
    if i < 0:
        return ""
    j = low.find(b"</title>", i)
    return html[i + 7:j].decode("utf-8", "replace").strip() if j > 0 else ""


def slug(s):
    for ch in "/!:.?=&":
        s = s.replace(ch, "_")
    return s.strip("_")


# ---------------- 命令 ----------------

def cmd_sessions():
    r = redis_conn()
    keys = r.cmd("KEYS", "edu:session:*") or []
    captchas = r.cmd("KEYS", "edu:captcha:*") or []
    print("教务会话（edu:session:*）：", len(keys))
    for k in keys:
        raw = r.cmd("GET", k)
        try:
            d = json.loads(raw)
            print(" -", k, "学号=", d.get("accountMasked"), "cookies=",
                  ",".join(p.split("=")[0] for p in (d.get("cookies") or "").split(";") if "=" in p))
        except Exception:
            print(" -", k, "(无法解析)")
    print("验证码会话（edu:captcha:*）：", len(captchas), "（TTL 5 分钟，属正常残留）")
    if not keys:
        print("\n⚠️ 还没有教务会话：需要先在小程序里用真实账号登录一次。")


def get_session(user_id):
    r = redis_conn()
    raw = r.cmd("GET", "edu:session:%s" % user_id)
    if not raw:
        print("找不到 edu:session:%s —— 请先在小程序登录教务" % user_id)
        sys.exit(1)
    return json.loads(raw)


def cmd_probe(cookie, label_filter=None, only_url=None):
    os.makedirs(OUT_DIR, exist_ok=True)
    rows = []
    targets = CANDIDATES
    if only_url:
        targets = [(label_filter or "自定义", only_url)]

    for label, path in targets:
        if label_filter and label_filter not in label and not only_url:
            continue
        body, meta = fetch(path, cookie)
        t = title_of(body)
        lower = body[:4000].decode("utf-8", "replace").lower()
        hint = ""
        if "登录" in t or "login" in meta["redirect"].lower():
            hint = "→ 未登录/被踢回登录页"
        elif meta["code"] == "404":
            hint = "→ 不存在"
        elif '"code"' in lower and "message" in lower:
            hint = "→ JSON 响应"

        name = "%s__%s" % (slug(label), slug(path))
        ext = "json" if hint.endswith("JSON 响应") else "html"
        with open(os.path.join(OUT_DIR, name + "." + ext), "wb") as f:
            f.write(body)

        rows.append((label, path, meta["code"], meta["size"], t[:28], hint))
        print("%-8s %-34s %s %8s  %-28s %s" % (label, path, meta["code"], meta["size"], t[:28], hint))
        time.sleep(0.6)

    print("\n落盘目录：", OUT_DIR)
    hit = [r for r in rows if r[2] in ("200", "302") and r[1] not in ("",) and "不存在" not in r[5]]
    if hit:
        print("可能有料的接口：")
        for r in hit:
            print("  ", r[0], r[1], "size=", r[3], "title=", r[4])
    else:
        print("这轮没有命中，需要从登录后的首页 HTML 里再抓菜单链接（--session 会顺带存首页）。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--discover", action="store_true", help="无 Cookie 探测 action 是否存在")
    ap.add_argument("--sessions", action="store_true", help="列出 Redis 里的教务会话")
    ap.add_argument("--session", help="用该 userId 的教务 Cookie 抓取")
    ap.add_argument("--url", help="只抓指定路径")
    ap.add_argument("--only", help="只抓某类（课表/成绩/考试/学期信息/首页/菜单）")
    args = ap.parse_args()

    if args.sessions:
        cmd_sessions()
        return
    if args.session:
        s = get_session(args.session)
        print("学号：", s.get("accountMasked"), "登录时间：", s.get("savedAt"))
        cmd_probe(s.get("cookies"), args.only, args.url)
        return
    # 默认：无 Cookie 探测
    print("== 无 Cookie 探测（只看接口是否存在）==")
    cmd_probe(None, args.only, args.url)


if __name__ == "__main__":
    main()
