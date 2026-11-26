#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · 教务页面抓取（登录成功后摸清课表/成绩/考试的真实入口与结构）

用法：
    EDU_ACC=... EDU_PWD=... python tools/edu_crawl.py --menu
    EDU_ACC=... EDU_PWD=... python tools/edu_crawl.py --get "/xsxxxggl/xxx.action"

产物：C:/Users/.../Temp/edu_page_*.html （原始页面，用于分析结构）
"""

import argparse
import json
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edu_sso_probe as m

SESSION_FILE = os.path.join(tempfile.gettempdir(), "edu_cookie.json")


def login_session():
    if os.environ.get("EDU_REUSE") == "1" and os.path.exists(SESSION_FILE):
        s = m.new_session()
        with open(SESSION_FILE, encoding="utf-8") as f:
            s.cookies.update(json.load(f))
        print("[i] 复用已保存的会话")
        return s
    s = m.do_login(os.environ["EDU_ACC"], os.environ["EDU_PWD"])
    if s:
        with open(SESSION_FILE, "w", encoding="utf-8") as f:
            json.dump({c.name: c.value for c in s.cookies}, f)
        print(f"[i] 会话已存 {SESSION_FILE}")
    return s


def show_menu(s):
    home = s.get(m.JXFW_BASE + "/login!welcome.action", timeout=30)
    print(f"[菜单] 首页 status={home.status_code} size={len(home.text)}")
    html = home.text

    # 菜单是 JS 拼的：$('#nav_tab').tabs('add',{title:'xx',href:'yy.action'})
    pairs = re.findall(r"title\s*:\s*['\"]([^'\"]{1,24})['\"]\s*,\s*href\s*:\s*['\"]([^'\"]+)['\"]", html)
    pairs += re.findall(r"href\s*:\s*['\"]([^'\"]+)['\"]\s*,\s*title\s*:\s*['\"]([^'\"]{1,24})['\"]", html)
    seen, rows = set(), []
    for a, b in pairs:
        if a.startswith("/") or ".action" in a:
            href, title = a, b
        else:
            href, title = b, a
        if href in seen:
            continue
        seen.add(href)
        rows.append((title, href))

    print(f"[菜单] 解析到 {len(rows)} 项")
    for title, href in rows:
        print(f"  {title[:20]:<22} {href[:95]}")

    kw = ("课", "成绩", "考试", "培养", "学分", "绩点", "课表", "排课")
    hit = [(t, h) for t, h in rows if any(k in t for k in kw)]
    print(f"\n[重点] 与课表/成绩/考试相关 {len(hit)} 项")
    for t, h in hit:
        print(f"  ★ {t[:20]:<22} {h[:95]}")

    # 兜底：把所有 .action 链接列出来
    all_action = sorted(set(re.findall(r"['\"]([A-Za-z0-9_/!\.\-]+\.action[^'\"]*)['\"]", html)))
    print(f"\n[兜底] 页面里出现的 .action 共 {len(all_action)} 个")
    for a in all_action[:40]:
        print("   ", a[:100])
    return rows


def get_page(s, path):
    if not path.startswith("http"):
        path = m.JXFW_BASE + "/" + path.lstrip("/")
    r = s.get(path, timeout=30)
    ct = r.headers.get("Content-Type", "")
    print(f"[GET] {r.status_code} {len(r.content)}B {ct[:40]}  {path[:90]}")
    safe = re.sub(r"[^a-zA-Z0-9]+", "_", path)[-40:]
    out = os.path.join(tempfile.gettempdir(), f"edu_page_{safe}.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(r.text)
    print(f"[i] 已存 {out}")
    if "json" in ct:
        print(r.text[:500])
    else:
        t = re.search(r"<title>([^<]{0,60})</title>", r.text)
        print(f"    title={t.group(1).strip() if t else ''}")
        tables = len(re.findall(r"<table", r.text, re.I))
        print(f"    <table> 数量={tables}")
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--menu", action="store_true")
    ap.add_argument("--get", default="")
    args = ap.parse_args()
    s = login_session()
    if not s:
        print("[×] 登录失败")
        return 1
    if args.menu:
        show_menu(s)
    if args.get:
        get_page(s, args.get)
    return 0


if __name__ == "__main__":
    sys.exit(main())
