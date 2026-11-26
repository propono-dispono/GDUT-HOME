# -*- coding: utf-8 -*-
"""
修复 /opt/docker/mysql-run.sh 里的 MYSQL_ROOT_PASSWORD 拼接错误。
症状：该行被写成了  <32位密码>"$(cat "$SECRETS/root-password")"  ⇒ 拼出 64 位，重建容器必挂。
目标：改为只读 secrets/root-password 一处，并把该文件写成真实密码。
"""
import io
import os
import sys

SCRIPT = '/opt/docker/mysql-run.sh'
SECRET_FILE = '/opt/docker/mysql-secrets/root-password'
ROOT_PW_FILE = '/root/.mysql-docker-root-password'

real = io.open(ROOT_PW_FILE, encoding='utf-8').read().strip()
if len(real) != 32:
    print('ABORT: 真实 root 密码长度异常 = %d' % len(real))
    sys.exit(1)

# 1. secrets 文件写成真实密码（脚本只认这一处）
io.open(SECRET_FILE, 'w', encoding='utf-8').write(real)
os.chmod(SECRET_FILE, 0o600)
print('secrets/root-password 已同步为真实密码，长度 %d' % len(real))

# 2. 重写脚本里的那一行
src = io.open(SCRIPT, encoding='utf-8').read()
lines = src.split('\n')
hits = 0
for i, line in enumerate(lines):
    if 'MYSQL_ROOT_PASSWORD=' not in line:
        continue
    indent = line[:len(line) - len(line.lstrip())]
    trail = ' \\' if line.rstrip().endswith('\\') else ''
    lines[i] = indent + '-e MYSQL_ROOT_PASSWORD="$(cat "$SECRETS/root-password")"' + trail
    hits += 1
    print('  第 %d 行已重写' % (i + 1))

if hits == 0:
    print('ABORT: 没找到 MYSQL_ROOT_PASSWORD 行')
    sys.exit(1)

io.open(SCRIPT, 'w', encoding='utf-8').write('\n'.join(lines))
print('脚本已更新，共修改 %d 行' % hits)
