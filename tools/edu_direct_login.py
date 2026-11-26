#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校集 · 广工教务「直接登录」探测（SSO 不可用时的备选路径）

两步走，因为图形验证码需要人/模型识别：
    # 第 1 步：取验证码图片，记下 session
    EDU_ACC=... python tools/edu_direct_login.py --get-captcha
        → 输出图片路径，用眼睛/模型读出 4 位码

    # 第 2 步：带验证码提交
    EDU_ACC=... EDU_PWD=... EDU_CAPTCHA=8f3k python tools/edu_direct_login.py --submit

协议（2026-10-20 从官方前端源码确认）：
    GET  /login!welcome.action            → JSESSIONID
    GET  /yzm?d=<ts>                      → 验证码 JPEG（绑 JSESSIONID）
    POST /new/login  account / pwd / verifycode
    pwd = hex( AES-ECB-128( key = verifycode×4, 明文=密码原文 ) )
"""

import argparse
import base64
import json
import os
import re
import sys
import tempfile
import time

import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

JXFW = "https://jxfw.gdut.edu.cn"
CAP_IMG = os.path.join(tempfile.gettempdir(), "edu_captcha.jpg")
CAP_META = os.path.join(tempfile.gettempdir(), "edu_captcha.json")


def new_session(cookies=None):
    s = requests.Session()
    s.trust_env = False
    s.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    if cookies:
        s.cookies.update(cookies)
    return s


def encrypt_pwd(pwd, captcha):
    key = (captcha * 4).encode("utf-8")
    c = AES.new(key, AES.MODE_ECB)
    return c.encrypt(pad(pwd.encode("utf-8"), 16)).hex()


def get_captcha():
    s = new_session()
    r0 = s.get(JXFW + "/login!welcome.action", timeout=25)
    print(f"[1] 教务登录页 status={r0.status_code} cookie={list(s.cookies.keys())}")
    r = s.get(JXFW + f"/yzm?d={int(time.time()*1000)}", timeout=25)
    ct = r.headers.get("Content-Type", "")
    print(f"[2] 取验证码 status={r.status_code} type={ct} size={len(r.content)}")
    if r.status_code != 200 or len(r.content) < 500:
        print("[×] 验证码拿不到")
        return 1
    with open(CAP_IMG, "wb") as f:
        f.write(r.content)
    with open(CAP_META, "w", encoding="utf-8") as f:
        json.dump({c.name: c.value for c in s.cookies}, f)
    print(f"[i] 图片: {CAP_IMG}")
    print(f"[i] cookie: {CAP_META}")
    return 0


def submit(acc, pwd, captcha):
    with open(CAP_META, encoding="utf-8") as f:
        cookies = json.load(f)
    s = new_session(cookies)
    form = {
        "account": acc,
        "pwd": encrypt_pwd(pwd, captcha),
        "verifycode": captcha,
    }
    r = s.post(JXFW + "/new/login", data=form, timeout=30,
               headers={"X-Requested-With": "XMLHttpRequest",
                        "Accept": "application/json, text/javascript, */*; q=0.01",
                        "Referer": JXFW + "/login!welcome.action",
                        "Origin": JXFW,
                        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
    print(f"[3] POST /new/login status={r.status_code}")
    txt = r.text.strip()
    print(f"    返回: {txt[:300]}")
    try:
        node = json.loads(txt)
    except Exception:
        node = {}
    if node.get("code", -1) >= 0:
        print("[✓] 登录成功")
        r2 = s.get(JXFW + "/", timeout=25, allow_redirects=True)
        print(f"[4] 首页 status={r2.status_code} final={r2.url[:100]}")
        print(f"[5] cookie: {sorted(s.cookies.keys())}")
        with open(os.path.join(tempfile.gettempdir(), "edu_session.json"), "w", encoding="utf-8") as f:
            json.dump({c.name: c.value for c in s.cookies}, f)
        print("[i] 会话已存 edu_session.json（敏感，用完删）")
        return 0
    print("[×] 登录未成功，看上面的 message")
    return 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--get-captcha", action="store_true")
    ap.add_argument("--submit", action="store_true")
    args = ap.parse_args()
    acc = os.environ.get("EDU_ACC", "").strip()
    if args.get_captcha:
        return get_captcha()
    if args.submit:
        pwd = os.environ.get("EDU_PWD", "")
        cap = os.environ.get("EDU_CAPTCHA", "").strip()
        if not (acc and pwd and cap):
            print("[×] 缺 EDU_ACC / EDU_PWD / EDU_CAPTCHA")
            return 2
        return submit(acc, pwd, cap)
    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
