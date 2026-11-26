#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · 广工教务「真实浏览器」登录 + 接口摸底

为什么要有这个脚本：
    纯 HTTP 复现 CAS 流程时，jxfw 的 /new/ssoLogin 收票后仍跳回自己，
    而 CAS 的 serviceValidate 明确返回 authenticationSuccess（票有效）。
    说明 jxfw 侧对浏览器环境有额外判断，需要用真浏览器跑通、把会话与请求抓下来。

用法：
    EDU_ACC=... EDU_PWD=... python tools/edu_browser_login.py
    EDU_ACC=... EDU_PWD=... python tools/edu_browser_login.py --probe

产物：
    /tmp/edu_state.json   playwright storage_state（含教务 Cookie，敏感，用完删）
    /tmp/edu_net.json     登录过程中关键请求的记录（URL/状态/类型，不含密码）
"""

import argparse
import json
import os
import re
import sys
import tempfile
import time

from playwright.sync_api import sync_playwright

JXFW = "https://jxfw.gdut.edu.cn"
SSO_ENTRY = JXFW + "/new/ssoLogin"
STATE = os.path.join(tempfile.gettempdir(), "edu_state.json")
NET = os.path.join(tempfile.gettempdir(), "edu_net.json")
ACC = os.environ.get("EDU_ACC", "").strip()
PWD = os.environ.get("EDU_PWD", "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--headless", action="store_true", help="无头模式（默认有头，便于观察）")
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--keep", action="store_true", help="结束后不关闭浏览器")
    args = ap.parse_args()

    if not ACC or not PWD:
        print("[×] 缺 EDU_ACC / EDU_PWD")
        return 2

    net = []
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=args.headless,
                                    args=["--disable-blink-features=AutomationControlled"])
        ctx = browser.new_context(
            locale="zh-CN",
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            viewport={"width": 1280, "height": 800},
        )
        page = ctx.new_page()

        def on_resp(r):
            u = r.url
            if any(k in u for k in ("login", "ssologin", "ssoLogin", "authserver", "jxfw")):
                ct = (r.headers or {}).get("content-type", "")
                net.append({"status": r.status, "url": u[:160], "type": ct[:40]})

        page.on("response", on_resp)

        print("[1] 打开教务 SSO 入口")
        page.goto(SSO_ENTRY, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(1500)
        print(f"    url={page.url[:110]}")

        if "authserver" not in page.url:
            print("[!] 没跳到 authserver —— 可能已有会话，先看是否直接登录成功")

        if "authserver" in page.url:
            # 账号登录表单：页面有三个 form（扫码/短信/账号），取带 pwdEncryptSalt 的那个
            form = page.locator("form:has(#pwdEncryptSalt)").first
            if form.count() == 0:
                print("[×] 找不到账号登录表单")
                for f in page.locator("form").all():
                    print("    form:", f.inner_html()[:80].replace("\n", " "))
                browser.close()
                return 1

            form.locator("input[name='username']").fill(ACC)
            # 可见的密码框没有 name（提交时由 login.js 把密文写进 #saltPassword）
            form.locator("input[placeholder='请输入密码']").fill(PWD)
            print("[2] 已填入账号密码")

            btn = form.locator("button, [type='submit'], a:has-text('登录')").first
            print(f"[3] 点击登录：{btn.inner_text()[:20] if btn.count() else 'submit'}")
            btn.click()

            try:
                page.wait_for_url(re.compile(r"jxfw\.gdut\.edu\.cn"), timeout=45000)
            except Exception:
                print("[!] 45s 内没跳回教务，当前 url=", page.url[:120])
                err = page.locator(".error, #msg, .alert").all_inner_texts()
                if err:
                    print("    页面提示:", [e.strip()[:60] for e in err if e.strip()][:3])

        page.wait_for_timeout(2000)
        print(f"[4] 最终 url={page.url[:120]}")

        # 再访问一次首页，确认会话可用
        page.goto(JXFW + "/", wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(1500)
        final = page.url
        # ⚠️ 登录后地址仍是 login!welcome.action，判据要看页面内容
        html = page.content()
        ok = ("注销" in html) and ("登录时间" in html)
        print(f"[5] 教务首页 url={final[:120]}  => {'✓ 已登录' if ok else '× 未登录'}")

        if ok:
            page.wait_for_timeout(3000)
            # 在页面内发请求：带浏览器自身的会话，绕开 cookie 复用的坑
            menu_html = page.evaluate("""async () => {
              const r = await fetch('/default!getMenu.action?_=' + Date.now(), {credentials:'include'});
              return await r.text();
            }""")
            p1 = os.path.join(tempfile.gettempdir(), "edu_menu.html")
            with open(p1, "w", encoding="utf-8") as f:
                f.write(menu_html)
            kws = [k for k in ("课表", "成绩", "考试", "选课", "培养方案", "学籍") if k in menu_html]
            print(f"[i] 菜单 {len(menu_html)}B → {p1}  含关键词={kws}")

            desk_html = page.evaluate("""async () => {
              const r = await fetch('/desktop!showDesktop.action?_=' + Date.now(), {credentials:'include'});
              return await r.text();
            }""")
            p2 = os.path.join(tempfile.gettempdir(), "edu_desktop.html")
            with open(p2, "w", encoding="utf-8") as f:
                f.write(desk_html)
            print(f"[i] 工作台 {len(desk_html)}B → {p2}")

            items = page.eval_on_selector_all(
                "#m2_container a",
                "els => els.map(e => [e.innerText.trim(), e.getAttribute('href') || '', e.getAttribute('onclick') || ''])")
            print(f"\n===== 左侧菜单 {len(items)} 项 =====")
            for t, h, oc in items:
                if not t:
                    continue
                print(f"  {t[:18]:<20} href={h[:60]:<62} onclick={oc[:50]}")

            # 打开某个页面并监听它内部发出的真实请求（EDU_GOTO）
            _goto = os.environ.get("EDU_GOTO", "").strip()
            if _goto:
                net.clear()
                page.goto(_goto if _goto.startswith("http") else JXFW + _goto,
                          wait_until="domcontentloaded", timeout=60000)
                page.wait_for_timeout(6000)
                print(f"\n===== 打开 {_goto[:60]} 后的内部请求 =====")
                for x in net:
                    if ".action" in x["url"] or "json" in x["type"]:
                        print(f"  {x['status']} {x['url'][:130]}")

            # 课表专项：打开页面 → 等渲染 → 打印所有请求 + datagrid url + 正文
            if os.environ.get("EDU_TT") == "1":
                net.clear()
                _tt = os.environ.get("EDU_TT_PATH", "/xsgrkbcx!xsgrkbMain.action")
                page.goto(JXFW + _tt + ("&" if "?" in _tt else "?") + "_=%d" % int(time.time() * 1000),
                          wait_until="domcontentloaded", timeout=60000)
                page.wait_for_timeout(9000)
                print(f"\n[TT] url={page.url[:110]}")
                print(f"[TT] frames={[f.url[:70] for f in page.frames]}")
                for x in net:
                    print(f"  NET {x['status']} {x['url'][:135]}")
                try:
                    txt = page.inner_text("body")
                    print("[TT] 正文:", re.sub(r"\s+", " ", txt)[:300])
                except Exception as e:
                    print("[TT] 正文读取失败", e)
                try:
                    cfg = page.evaluate("""() => {
                      const out = [];
                      document.querySelectorAll('table,div').forEach(el => {
                        try {
                          const dg = window.jQuery && window.jQuery(el).data('datagrid');
                          if (dg && dg.options && dg.options.url) out.push(dg.options.url);
                        } catch (e) {}
                      });
                      return out;
                    }""")
                    print("[TT] datagrid url:", cfg)
                except Exception as e:
                    print("[TT] datagrid 读取失败", e)

            if os.environ.get("EDU_TT") == "2":
                page.goto(JXFW + "/", wait_until="domcontentloaded", timeout=60000)
                page.wait_for_timeout(8000)
                html = page.evaluate("""() => {
                  const el = document.querySelector('#m2_container');
                  return el ? el.innerHTML.slice(0, 1500) : 'NO-EL';
                }""")
                print("\n[MENU]", re.sub(r"\s+", " ", html)[:1000])

            if os.environ.get("EDU_TT") == "3":
                page.goto(JXFW + "/", wait_until="domcontentloaded", timeout=60000)
                page.wait_for_timeout(9000)
                net.clear()
                page.click("td[onclick*='xsgrkbMain']")
                page.wait_for_timeout(9000)
                print("\n[CLICK] 已点击课表查询，frames=", [f.url[:80] for f in page.frames])
                for x in net:
                    print(f"  NET {x['status']} {x['url'][:140]}")
                for f in page.frames:
                    try:
                        if "xskbList" in f.url:
                            html = f.content()
                            fp = os.path.join(tempfile.gettempdir(), "edu_tt_frame.html")
                            with open(fp, "w", encoding="utf-8") as fh:
                                fh.write(html)
                            print(f"[TT] 课表 HTML {len(html)}B → {fp}")
                        t = f.inner_text("body")
                        t = re.sub(r"\s+", " ", t)
                        print(f"[FRAME {f.url[:50]}] {t[:200]}")
                    except Exception:
                        pass

            # 抓指定业务页面（EDU_PATHS 逗号分隔）
            for _i, pth in enumerate([x.strip() for x in os.environ.get("EDU_PATHS", "").split(",") if x.strip()]):
                try:
                    body = page.evaluate("""async (p) => {
                      const r = await fetch(p, {credentials:'include'});
                      return await r.text();
                    }""", pth)
                    name = f"p{_i}_" + re.sub(r"[^a-zA-Z0-9]+", "_", pth)[-26:]
                    fp = os.path.join(tempfile.gettempdir(), f"edu_{name}.html")
                    with open(fp, "w", encoding="utf-8") as f:
                        f.write(body)
                    tb = len(re.findall(r"<table", body, re.I))
                    tr = len(re.findall(r"<tr", body, re.I))
                    print(f"[抓取] {pth[:60]} → {len(body)}B  table={tb} tr={tr}  {fp}")
                except Exception as e:
                    print(f"[抓取] {pth} 失败: {e}")

        cookies = ctx.cookies()
        names = sorted({c["name"] for c in cookies})
        print(f"[6] cookie 名: {names}")
        ctx.storage_state(path=STATE)
        print(f"[i] 会话已存 {STATE}（敏感，用完删）")

        with open(NET, "w", encoding="utf-8") as f:
            json.dump(net, f, ensure_ascii=False, indent=1)
        print(f"[i] 请求记录 {len(net)} 条 → {NET}")

        if ok and args.probe:
            page.goto(JXFW + "/", wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(1200)
            links = page.eval_on_selector_all("a", "els => els.map(e => [e.innerText.trim(), e.getAttribute('href')])")
            keep = [(t, h) for t, h in links if h and ("课" in t or "成绩" in t or "考试" in t or "培养" in t)]
            print("\n===== 首页里和课表/成绩/考试相关的入口 =====")
            for t, h in keep[:20]:
                print(f"  {t[:16]:<18} {h[:90]}")

        if args.keep:
            input("按回车关闭浏览器…")
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
