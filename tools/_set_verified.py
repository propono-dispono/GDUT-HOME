#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把指定 openid 前缀的测试账号设为「已认证」，用于跑拼单/发帖的并发验证。

为什么需要它：发帖接口要求 verify_status=2（已认证），而 dev-login 新建的用户是 0（未认证）。
本机没有 mysql 客户端，所以用 Python 直连隧道端口（13307）。

用法：
    python tools/_set_verified.py            # 默认前缀 vgb_
    python tools/_set_verified.py test_      # 自定义前缀

说明：密码从 application-local.yml 现读，不写死、不打印。
"""
import re
import sys

import pymysql

YML = "xiaojii-server/src/main/resources/application-local.yml"
PREFIX = sys.argv[1] if len(sys.argv) > 1 else "vgb_"


def read_datasource():
    """从 local 配置里取 url / username / password，只取不打印。"""
    txt = open(YML, encoding="utf-8").read()
    url = re.search(r"url:\s*(jdbc:mysql://[^\s]+)", txt).group(1)
    host = re.search(r"//([\d.]+):(\d+)/(\w+)", url)
    user = re.search(r"username:\s*(\S+)", txt).group(1)
    pwd = re.search(r"password:\s*(\S+)", txt.split("datasource:")[1]).group(1)
    return host.group(1), int(host.group(2)), host.group(3), user, pwd


def main():
    h, p, db, user, pwd = read_datasource()
    print(f"连接 {h}:{p}/{db} user={user}")
    conn = pymysql.connect(host=h, port=p, user=user, password=pwd, database=db,
                           charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE user SET verify_status = 2, credit_score = 90 "
                "WHERE openid LIKE %s", (PREFIX + "%",))
            print(f"已更新 {cur.rowcount} 个账号为已认证（信用分 90）")
            cur.execute(
                "SELECT id, openid, verify_status, credit_score FROM user "
                "WHERE openid LIKE %s ORDER BY id", (PREFIX + "%",))
            for row in cur.fetchall():
                print("  ", row)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
