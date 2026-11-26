#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把指定帖子直接置为「展示中 + 人工审核通过」，用于并发验证。

为什么需要它：新用户（历史发布 = 0）发帖会进人工审核队列，status=0（待审核），
此时 joinGroupbuy 会因为"不是展示中"直接抛 1073，测不到并发逻辑。
验并发需要一条真正 ONLINE 的帖子。

用法：python tools/_publish_post.py 21
说明：密码从 application-local.yml 现读，不写死、不打印。
"""
import re
import sys

import pymysql

YML = "xiaojii-server/src/main/resources/application-local.yml"


def read_datasource():
    txt = open(YML, encoding="utf-8").read()
    url = re.search(r"url:\s*(jdbc:mysql://[^\s]+)", txt).group(1)
    m = re.search(r"//([\d.]+):(\d+)/(\w+)", url)
    user = re.search(r"username:\s*(\S+)", txt).group(1)
    pwd = re.search(r"password:\s*(\S+)", txt.split("datasource:")[1]).group(1)
    return m.group(1), int(m.group(2)), m.group(3), user, pwd


def main():
    if len(sys.argv) < 2:
        print("用法：python tools/_publish_post.py <postId>")
        sys.exit(1)
    pid = int(sys.argv[1])

    h, p, db, user, pwd = read_datasource()
    conn = pymysql.connect(host=h, port=p, user=user, password=pwd, database=db,
                           charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            # status=1 展示中；audit_status=2 人工通过
            cur.execute("UPDATE post SET status = 1, audit_status = 2 WHERE id = %s", (pid,))
            print(f"post {pid} 已放行（影响 {cur.rowcount} 行）")
            cur.execute("SELECT id, status, audit_status FROM post WHERE id = %s", (pid,))
            print("  ", cur.fetchone())
    finally:
        conn.close()


if __name__ == "__main__":
    main()
