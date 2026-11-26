"""把一份 SQL 迁移文件执行到本地库（经 SSH 隧道 127.0.0.1:13307）。

【一句话】读 sql 文件 → 按分号切开 → 一条条执行 → 打印表结构确认结果。

用法：
    python tools/apply_migration.py xiaojii-server/sql/migration_20261115_errand_take.sql

为什么做成脚本而不是每次手敲 mysql 命令：
  1. 本机没装 mysql 客户端，只能用 pymysql；
  2. 密码在 application-local.yml 里，脚本自己读，不用把密码贴进命令行（会留在 history 里）；
  3. 迁移文件里有多条语句 + 大量注释，手工拆容易漏。

⚠️ 前提：SSH 隧道已建立（13307 -> 服务器 3306）。
   没建隧道时会报连接失败，先跑「启动隧道.bat」。
"""
import re
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parent.parent
LOCAL_YML = ROOT / "xiaojii-server" / "src" / "main" / "resources" / "application-local.yml"


def read_password() -> str:
    """从 application-local.yml 里抠出 datasource 的密码，避免写死在命令行。"""
    text = LOCAL_YML.read_text(encoding="utf-8")
    # 只取第一个 password（datasource 段），后面的 redis password 不取
    m = re.search(r"password:\s*(\S+)", text)
    if not m:
        raise SystemExit(f"没在 {LOCAL_YML} 里找到 password")
    return m.group(1)


def strip_comments(sql: str) -> str:
    """去掉整行的 -- 注释。

    只删整行注释，不删行尾注释 —— 行尾注释可能是字符串内容的一部分，
    粗暴删除会把 DDL 里的 COMMENT '...' 改坏。
    """
    return "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("用法：python tools/apply_migration.py <sql文件路径>")

    sql_path = Path(sys.argv[1])
    if not sql_path.is_absolute():
        sql_path = ROOT / sql_path
    if not sql_path.exists():
        raise SystemExit(f"文件不存在：{sql_path}")

    body = strip_comments(sql_path.read_text(encoding="utf-8"))
    statements = [s.strip() for s in body.split(";") if s.strip()]

    conn = pymysql.connect(
        host="127.0.0.1", port=13307, user="root",
        password=read_password(), database="xiaojii",
        charset="utf8mb4", autocommit=True,
    )
    try:
        cur = conn.cursor()
        for i, stmt in enumerate(statements, 1):
            # 只打印语句开头，避免刷屏
            head = " ".join(stmt.split())[:70]
            cur.execute(stmt)
            print(f"[{i}/{len(statements)}] OK  {head}...")
        print(f"\n迁移完成：{sql_path.name}（{len(statements)} 条语句）")

        # 顺手把刚建的表结构打出来，肉眼确认字段和索引对不对
        cur.execute("SHOW TABLES LIKE 'errand_take'")
        if cur.fetchone():
            cur.execute("SHOW INDEX FROM errand_take")
            print("\nerrand_take 索引：")
            for row in cur.fetchall():
                unique = "UNIQUE" if row[1] == 0 else "      "
                print(f"  {unique}  {row[2]:<16} 列={row[4]}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
