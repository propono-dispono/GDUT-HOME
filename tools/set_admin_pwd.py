"""生成管理员强密码、PBKDF2 哈希，并写入校集凭据文件（不打印明文）。"""
import base64
import hashlib
import io
import os
import secrets

CRED = r"E:\WorkBuddy输出\凭据\校集-凭据.md"
SECTION = "## 管理后台（admin）"

alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
pwd = "".join(secrets.choice(alphabet) for _ in range(16))

salt = os.urandom(16)
iterations = 120_000
dk = hashlib.pbkdf2_hmac("sha256", pwd.encode(), salt, iterations, 32)
b64 = lambda b: base64.b64encode(b).decode()
stored = f"pbkdf2${iterations}${b64(salt)}${b64(dk)}"

txt = io.open(CRED, encoding="utf-8").read()
if SECTION in txt:
    txt = txt.split(SECTION)[0].rstrip() + "\n"

txt += (
    "\n\n"
    + SECTION
    + "\n\n| 项 | 值 |\n|---|---|\n"
    + "| 入口 | https://xj.sxz-blog.xyz/admin/ |\n"
    + "| 账号 | admin |\n"
    + "| 密码 | " + pwd + " |\n"
    + "| 更新时间 | 2026-10-19 16:54 |\n\n"
    + "> 已替换初始密码 admin123。遗忘时用 PasswordUtil 生成新哈希后 UPDATE admin_user。\n"
)
io.open(CRED, "w", encoding="utf-8", newline="\n").write(txt)

io.open(
    r"E:\SchoolMarket\tools\_set_admin.sql",
    "w",
    encoding="utf-8",
    newline="\n",
).write(
    "UPDATE admin_user SET password_hash='" + stored + "' WHERE username='admin';\n"
)

print("HASH=" + stored)
print("pwd_len=" + str(len(pwd)))
