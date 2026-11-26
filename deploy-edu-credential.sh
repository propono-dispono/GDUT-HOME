#!/usr/bin/env bash
# 教务「记住密码」功能部署脚本
# 用法：在 Git Bash 里执行  bash deploy-edu-credential.sh
# 过程中需要输入 2~3 次服务器密码
set -euo pipefail

HOST="root@8.134.171.71"
JAR="xiaojii-server/target/xiaojii-server-1.0.0.jar"
KEY_FILE="/e/WorkBuddy输出/凭据/edu-cred-key-20261112.txt"
CONTAINER="xiaojii-server"

echo "===== 0/4 本地检查 ====="
[ -f "$JAR" ] || { echo "❌ 找不到 jar：$JAR"; exit 1; }
[ -f "$KEY_FILE" ] || { echo "❌ 找不到密钥文件：$KEY_FILE"; exit 1; }
echo "✅ jar: $(ls -lh "$JAR" | awk '{print $5}')"
echo "✅ 密钥文件存在（内容不打印）"

echo
echo "===== 1/4 上传 jar（会提示输入密码）====="
scp -o StrictHostKeyChecking=no "$JAR" "$HOST:/opt/xiaojii/app.jar.new"

echo
echo "===== 2/4 上传密钥（会提示输入密码）====="
scp -o StrictHostKeyChecking=no "$KEY_FILE" "$HOST:/opt/xiaojii/.edu-cred-key.tmp"

echo
echo "===== 3/4 服务器端替换与重启（会提示输入密码）====="
ssh -o StrictHostKeyChecking=no "$HOST" 'bash -s' <<REMOTE
set -e
cd /opt/xiaojii

echo "--- 备份现有 jar 与 .env ---"
STAMP=\$(date +%Y%m%d-%H%M%S)
cp -f app.jar "app.jar.bak-\$STAMP"
cp -f .env    ".env.bak-\$STAMP"
echo "备份完成：app.jar.bak-\$STAMP / .env.bak-\$STAMP"

echo "--- 替换 jar ---"
mv -f app.jar.new app.jar
chmod 644 app.jar

echo "--- 写入 EDU_CRED_KEY ---"
KEY=\$(tr -d '\r\n' < .edu-cred-key.tmp)
grep -v '^EDU_CRED_KEY=' .env > .env.new || true
printf 'EDU_CRED_KEY=%s\n' "\$KEY" >> .env.new
mv -f .env.new .env
chmod 600 .env
rm -f .edu-cred-key.tmp
echo "密钥已写入 .env（权限 600，不打印内容）"

echo "--- 重启容器 ---"
docker restart $CONTAINER
sleep 10

echo "--- 容器状态 ---"
docker ps --filter "name=$CONTAINER" --format '{{.Names}} {{.Status}}'

echo "--- 启动日志（找密钥加载记录）---"
docker logs --tail 60 $CONTAINER 2>&1 | grep -i "教务凭据" || echo "（未找到『教务凭据』日志，稍等几秒再看）"

echo "--- 健康检查 ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:8081/api/app/announcement/current || true
REMOTE

echo
echo "===== 4/4 部署完成 ====="
echo "回滚方式：ssh $HOST 后执行"
echo "  cd /opt/xiaojii && cp -f app.jar.bak-<时间戳> app.jar && cp -f .env.bak-<时间戳> .env && docker restart $CONTAINER"
