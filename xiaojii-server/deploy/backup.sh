#!/usr/bin/env bash
# 校集数据库每日备份：保留 7 天
set -euo pipefail

DIR=/opt/backup/xiaojii
mkdir -p "$DIR"
chmod 700 "$DIR"

# root 密码文件由 MySQL 容器部署时保存，权限 600
ROOT_PWD=$(cat /root/.mysql-docker-root-password)

FILE="$DIR/xiaojii-$(date +%F).sql.gz"

docker exec -e MYSQL_PWD="$ROOT_PWD" MySQL8.0 \
  mysqldump -uroot --single-transaction --quick --routines --events \
  --default-character-set=utf8mb4 xiaojii | gzip > "$FILE.tmp"

mv "$FILE.tmp" "$FILE"
chmod 600 "$FILE"

# 只保留最近 7 天
find "$DIR" -name 'xiaojii-*.sql.gz' -mtime +7 -delete

echo "$(date '+%F %T') OK $(du -h "$FILE" | cut -f1)" >> /opt/xiaojii/backup.log
