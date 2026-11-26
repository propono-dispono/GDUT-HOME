# 校集 · 部署运行手册（服务器实测版）

> **⚠️ 2026-10-19 已按本手册完成上线。实际采用 `--network host`（不是 xiaojii-net）：**
> MySQL/Redis 只监听宿主 `127.0.0.1`，host 网络可直接复用，且应用只绑定 `127.0.0.1:8081`。
> 实际命令与验证结果见 `E:\SchoolMarket\校集-上线部署记录-20261019.md`；
> 下文第 3、5 节的 `xiaojii-net` 写法作为**备选方案**保留。

> 服务器：`root@8.134.171.71`（Ubuntu 22.04 / 2 vCPU / 1608MB）
> 预检时间：2026-10-19 16:30（以下路径与工具均已实测存在）

## 0. 预检结论（已核对）

| 项 | 实测 |
|---|---|
| certbot | `/usr/bin/certbot`，版本 1.21.0 |
| Nginx 站点目录 | `/etc/nginx/sites-enabled/`（已有 `sxz-blog`、`xing-portal`） |
| 证书挑战目录 | `/var/www/html` 存在 |
| 应用目录 | `/opt/xiaojii` **不存在，需创建** |
| 8081 端口 | 空闲 |
| Java 镜像 | `eclipse-temurin:21-jre-alpine` 已存在，无需拉取 |
| Docker 网络 | 无 `xiaojii-net`；MySQL8.0 / Redis7 目前在 `bridge` |
| MySQL / Redis | 只监听 `127.0.0.1:3307` / `127.0.0.1:6379` |

> ⚠️ 不要动 `blog-server`、`blog-portal`、Nginx 现有站点、1Panel（10086）。

---

## 1. 本地构建 jar

```bash
cd /e/SchoolMarket/xiaojii-server
./mvnw -DskipTests package
ls -lh target/xiaojii-server-1.0.0.jar
```

预期：`BUILD SUCCESS`，jar 约 49MB。

---

## 2. 服务器准备目录并上传 jar

```bash
ssh -i C:\Users\damn\.ssh\id_ed25519_sxzserver root@8.134.171.71
mkdir -p /opt/xiaojii/uploads /opt/xiaojii/admin
chmod 755 /opt/xiaojii /opt/xiaojii/uploads
exit
```

```bash
cd /e/SchoolMarket/xiaojii-server
scp -i C:/Users/damn/.ssh/id_ed25519_sxzserver \
  target/xiaojii-server-1.0.0.jar \
  root@8.134.171.71:/opt/xiaojii/app.jar
```

预期：`100%` 传输完成；服务器上 `ls -lh /opt/xiaojii/app.jar` 约 49MB。

---

## 3. 建容器网络并接入 MySQL / Redis

```bash
docker network create xiaojii-net
docker network connect xiaojii-net MySQL8.0
docker network connect xiaojii-net Redis7
docker network inspect xiaojii-net --format '{{range .Containers}}{{.Name}} {{end}}'
```

预期输出包含：`MySQL8.0 Redis7`。
（`connect` 不会重启容器，现有 blog 服务不受影响。）

---

## 4. 写环境变量文件（含密钥，权限 600）

```bash
# 本地把模板传到服务器（只需一次）
cd /e/SchoolMarket/xiaojii-server
scp -i C:/Users/damn/.ssh/id_ed25519_sxzserver deploy/env.example \
  root@8.134.171.71:/opt/xiaojii/.env.example

# 服务器上复制成正式配置并收紧权限
cp /opt/xiaojii/.env.example /opt/xiaojii/.env
chmod 600 /opt/xiaojii/.env
nano /opt/xiaojii/.env
```

必须替换的值：

| 变量 | 值 |
|---|---|
| `DB_PASSWORD` | 服务器 MySQL root 密码（见 `E:\WorkBuddy输出\凭据\sxz-blog-服务器凭据.md`） |
| `JWT_SECRET` | `openssl rand -base64 48` 生成 |
| `WX_APPID` | `wx93309d649e66dc53` |
| `WX_SECRET` | 小程序后台重置得到（用户提供，**不进聊天/仓库**） |
| `WX_DEV_LOGIN` | `false` |

> MySQL 容器网络内端口是 `3306`（不是宿主映射的 3307），`DB_URL` 用 `MySQL8.0:3306`。

---

## 5. 启动容器（只绑回环，公网不可直连）

```bash
docker run -d \
  --name xiaojii-server \
  --restart unless-stopped \
  --network xiaojii-net \
  -p 127.0.0.1:8081:8081 \
  --memory 448m --memory-swap 640m --cpus 1.0 \
  --env-file /opt/xiaojii/.env \
  -v /opt/xiaojii/app.jar:/app/app.jar:ro \
  -v /opt/xiaojii/uploads:/app/uploads \
  eclipse-temurin:21-jre-alpine \
  sh -c 'exec java -Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Duser.timezone=Asia/Shanghai -jar /app/app.jar --server.port=8081'
```

验证：

```bash
docker logs --tail 60 xiaojii-server
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/doc.html
```

预期：日志出现 `Started XiaojiiApplication`；curl 返回 `200`。

---

## 6. DNS（用户操作，见 `校集-你要做的两步-20261019.md`）

验证：

```bash
nslookup xj.sxz-blog.xyz 223.5.5.5
```

预期：`Address: 8.134.171.71`。

---

## 7. 签证书（HTTP-01 webroot）

```bash
certbot certonly --webroot -w /var/www/html -d xj.sxz-blog.xyz \
  --agree-tos --register-unsafely-without-email --non-interactive
```

预期：`Congratulations! Your certificate and chain have been saved at:
/etc/letsencrypt/live/xj.sxz-blog.xyz/fullchain.pem`

> 如果 80 端口被现有 Nginx 占用导致挑战失败，改用 `--nginx` 或临时放行。

---

## 8. 配 Nginx

```bash
# 本地：上传站点配置
cd /e/SchoolMarket/xiaojii-server
scp -i C:/Users/damn/.ssh/id_ed25519_sxzserver deploy/nginx-xj.conf \
  root@8.134.171.71:/etc/nginx/sites-enabled/xj

# 服务器：校验并重载
nginx -t
systemctl reload nginx
curl -s -o /dev/null -w '%{http_code}\n' https://xj.sxz-blog.xyz/api/doc.html
```

预期：`nginx: configuration file /etc/nginx/nginx.conf test is successful`；curl 返回 `200`。

---

## 9. 部署管理后台（审核/认证入口）

```bash
# 本地构建（outDir 用新目录，避开工作区删除策略）
cd /e/SchoolMarket/mall-admin-web
node ./node_modules/vite/bin/vite.js build --outDir dist-build --emptyOutDir

scp -r -i C:/Users/damn/.ssh/id_ed25519_sxzserver dist-build/* \
  root@8.134.171.71:/opt/xiaojii/admin/
```

在 `nginx-xj.conf` 里取消 `/admin/` 段落注释，`nginx -t && systemctl reload nginx`。
访问：`https://xj.sxz-blog.xyz/admin/`。

> 后台 `.env.production` 的 `VITE_BASE_SERVER_URL` 必须是 `https://xj.sxz-blog.xyz/api`。

---

## 10. 上线后回归

```bash
# 订单接口应 404（B 方案）
curl -s https://xj.sxz-blog.xyz/api/app/order/list

# 文档
curl -s -o /dev/null -w '%{http_code}\n' https://xj.sxz-blog.xyz/api/doc.html

# 图片地址应为 https
curl -s https://xj.sxz-blog.xyz/api/app/post/feed?page=1\&size=1 | head -c 300
```

真机走一遍：登录 → 认证 → 发布 → 后台审核 → 浏览 → 私信 → 收藏 → 退出。

---

## 11. 回滚

```bash
docker rm -f xiaojii-server
rm -f /etc/nginx/sites-enabled/xj
nginx -t && systemctl reload nginx
```

数据不受影响：MySQL 在容器里，上传目录在 `/opt/xiaojii/uploads`。
