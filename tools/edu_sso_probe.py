#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · 广工统一身份认证（CAS SSO）探测脚本

用途：用真实账号走一次 authserver 登录，拿到 jxfw 教务会话 Cookie，
      然后探测课表/成绩/考试的候选接口。
      密码只从环境变量读取，不落盘、不打印。

用法：
    EDU_ACC=3124004295 EDU_PWD='******' python tools/edu_sso_probe.py --login
    EDU_ACC=... EDU_PWD=... python tools/edu_sso_probe.py --login --probe

协议（2026-10-20 从官方前端源码确认）：
    GET  https://authserver.gdut.edu.cn/authserver/login?service=<jxfw ssologin>
         → 取 pwdEncryptSalt / execution / JSESSIONID
    POST 同上 URL，form: username / password / lt / dllt / cllt / execution / _eventId
         password = Base64( AES-CBC( randomString(64) + 明文, key=salt, iv=salt ) )
         官方 iv 是随机 16 位，但明文前拼了 64 字节随机串，
         服务端忽略前 64 字节即可，iv 不一致只影响第 1 块，不影响密码区。
    → 302 到 https://jxfw.gdut.edu.cn/new/ssologin?ticket=ST-...
"""

import argparse
import base64
import json
import os
import random
import re
import sys
from urllib.parse import urljoin

import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

AUTH_BASE = "https://authserver.gdut.edu.cn"
JXFW_BASE = "https://jxfw.gdut.edu.cn"
SERVICE = JXFW_BASE + "/new/ssoLogin"
CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"


def rand_str(n):
    return "".join(random.choice(CHARS) for _ in range(n))


def encrypt_password(plain, salt):
    """官方 encryptAES：64 位随机前缀 + AES-CBC/Pkcs7，Base64 输出。"""
    data = rand_str(64) + plain
    key = salt.encode("utf-8")
    iv = salt.encode("utf-8")
    cipher = AES.new(key, AES.MODE_CBC, iv)
    return base64.b64encode(cipher.encrypt(pad(data.encode("utf-8"), 16))).decode()


def new_session():
    s = requests.Session()
    s.trust_env = False          # 不走系统代理（本机代理连不上校内站）
    s.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    return s


def resolve_login_url(sess):
    """先访问教务的 SSO 入口，由它给出带准确 service 参数的 authserver 地址。"""
    r = sess.get(JXFW_BASE + "/new/ssoLogin", timeout=25, allow_redirects=False)
    loc = r.headers.get("Location", "")
    print(f"[0] GET /new/ssoLogin  status={r.status_code}  → {loc[:110]}")
    if "authserver" in loc:
        return loc
    print("[!] 未跳转到 authserver，退回手工拼 service")
    return f"{AUTH_BASE}/authserver/login?service={SERVICE}"


def do_login(acc, pwd):
    # 教务与认证中心分两个会话：两个域都有同名 JSESSIONID，共用会互相覆盖
    jx = new_session()
    auth = jx if os.environ.get("EDU_SINGLE") == "1" else new_session()

    # 先访问教务登录页，让 jxfw 下发 JSESSIONID（否则 ssoLogin 只能用 URL 重写）
    warm = jx.get(JXFW_BASE + "/login!welcome.action", timeout=25)
    print(f"[0a] 预热教务页 status={warm.status_code} jxfw cookie={list(jx.cookies.keys())}")

    login_url = resolve_login_url(jx)
    r = auth.get(login_url, timeout=25)
    print(f"[1] GET 登录页  status={r.status_code} size={len(r.content)}")
    salt_m = re.search(r'id="pwdEncryptSalt"[^>]*value="([^"]+)"', r.text)
    exec_m = re.search(r'id="execution"[^>]*name="execution"[^>]*value="([^"]+)"'
                       r'|name="execution"[^>]*id="execution"[^>]*value="([^"]+)"', r.text)
    if not salt_m:
        print("[×] 页面里没找到 pwdEncryptSalt，页面结构可能变了")
        return None
    salt = salt_m.group(1)
    execution = (exec_m.group(1) or exec_m.group(2)) if exec_m else "e1s1"
    print(f"[2] salt 长度={len(salt)}  execution={execution}  "
          f"auth cookie={list(auth.cookies.keys())}  jxfw cookie={list(jx.cookies.keys())}")

    form = {
        "username": acc,
        "password": encrypt_password(pwd, salt),
        "lt": "",
        "dllt": "generalLogin",
        "cllt": "userNameLogin",
        "execution": execution,
        "_eventId": "submit",
        "rmShown": "1",
    }
    r2 = auth.post(login_url, data=form, allow_redirects=False, timeout=30)
    loc = r2.headers.get("Location", "")
    print(f"[3] POST 登录  status={r2.status_code}  location={loc[:120] or '(无)'}")

    if r2.status_code in (301, 302, 303):
        if "ticket=" in loc:
            print("[✓] 已拿到 ticket，密码校验通过")
            if os.environ.get("EDU_VALIDATE") == "1":
                tk = loc.split("ticket=")[1].split("&")[0]
                svc = SERVICE
                for ep in ("serviceValidate", "p3/serviceValidate"):
                    vr = auth.get(f"{AUTH_BASE}/authserver/{ep}",
                                  params={"ticket": tk, "service": svc,
                                          "format": "JSON"}, timeout=20)
                    print(f"[3.5] {ep} {vr.status_code}: {vr.text[:260]}")
            r3 = jx.get(urljoin(AUTH_BASE, loc), timeout=30, allow_redirects=False,
                        headers={"Referer": AUTH_BASE + "/",
                                 "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"})
            print(f"[4] GET ssoLogin  status={r3.status_code}  "
                  f"location={r3.headers.get('Location', '')[:90] or '(无)'}  "
                  f"len={len(r3.content)}")
            if r3.status_code >= 400 or r3.status_code == 200:
                body = re.sub(r"<[^>]+>", " ", r3.text)
                body = re.sub(r"\s+", " ", body).strip()
                print(f"     正文: {body[:300]}")
            if r3.status_code == 200:
                import re as _re
                t = _re.search(r"<title>([^<]{0,40})</title>", r3.text)
                hint = _re.findall(r"[^<>\s]{0,12}(?:ticket|票据)[^<>\s]{0,20}", r3.text)[:3]
                body = _re.sub(r"<[^>]+>", " ", r3.text)
                body = _re.sub(r"\s+", " ", body).strip()
                print(f"     title={t.group(1).strip() if t else ''}  ticket提示={hint}")
                print(f"     正文摘要: {body[:200]}")
        else:
            print("[×] 302 但没有 ticket —— 大概率密码错误或有额外校验")
            return None
    else:
        body = r2.text
        m = re.search(r'id="msg"[^>]*>([^<]{2,60})<', body) or \
            re.search(r'class="[^"]*error[^"]*"[^>]*>([^<]{2,60})<', body)
        print(f"[×] 未跳转，页面提示：{m.group(1).strip() if m else '（未识别）'}")
        return None

    r4 = jx.get(JXFW_BASE + "/", timeout=30, allow_redirects=True)
    print(f"[5] 访问教务首页  status={r4.status_code}  final={r4.url[:100]}")
    print(f"[6] 当前 cookie: {sorted(jx.cookies.keys())}")
    # ⚠️ 不能用 URL 判断登录态：登录成功后地址仍是 /login!welcome.action，
    #    真正的判据是页面里有没有工作台元素（注销 / 登录时间 / 修改密码）
    ok = ("注销" in r4.text) and ("登录时间" in r4.text)
    print("[✓] 教务会话已建立" if ok else "[×] 仍被弹回登录页")
    return jx if ok else None


def probe(sess):
    """在已登录会话上探候选接口，只打印状态码与片段，用于人工判断。"""
    cands = [
        "/", "/index.jsp", "/xsxxxggl/xsgrxx!queryXsgrxx.action",
        "/student/courseSelect/thisSemesterCurriculum/index",
        "/student/courseSelect/courseSelectByAjax/index",
        "/score/queryScore.action", "/cjcx/queryScoreList.action",
        "/ksxx/ksxxList.action", "/student/examSchedule/index",
        "/kbcx/kbcxList.action", "/framework/xsMain.jsp",
    ]
    print("\n===== 接口探测 =====")
    for p in cands:
        try:
            r = sess.get(JXFW_BASE + p, timeout=20, allow_redirects=False)
            loc = r.headers.get("Location", "")
            kind = "HTML"
            ct = r.headers.get("Content-Type", "")
            if "json" in ct:
                kind = "JSON"
            title = ""
            if kind == "HTML" and r.status_code == 200:
                m = re.search(r"<title>([^<]{0,40})</title>", r.text)
                title = m.group(1).strip() if m else ""
            print(f"  {r.status_code:<4} {kind:<4} {p[:52]:<54} {('→' + loc[:40]) if loc else title}")
        except Exception as e:
            print(f"  ERR  {p}  {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--login", action="store_true")
    ap.add_argument("--probe", action="store_true")
    ap.add_argument("--save-cookie", default="")
    args = ap.parse_args()

    acc = os.environ.get("EDU_ACC", "").strip()
    pwd = os.environ.get("EDU_PWD", "")
    if not acc or not pwd:
        print("[×] 缺 EDU_ACC / EDU_PWD 环境变量")
        return 2

    got = do_login(acc, pwd)
    if not got:
        return 1
    sess = got

    if args.save_cookie:
        with open(args.save_cookie, "w", encoding="utf-8") as f:
            json.dump({c.name: c.value for c in sess.cookies}, f)
        print(f"[i] cookie 已存 {args.save_cookie}（含会话凭证，用完请删）")

    if args.probe:
        probe(sess)
    return 0


if __name__ == "__main__":
    sys.exit(main())
