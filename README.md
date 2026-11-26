# GDUT-HOME · 广东工业大学校园互助平台

> 小程序端的品牌字样为 **GDUT-HOME**；项目内部代号仍沿用「校集」，因此根目录的设计文档命名为 `校集-*.md`、后端包名为 `com.gdut.xiaojii`。

面向校内学生的互助信息平台：微信小程序（用户端）+ Spring Boot 后端 + Vue3 管理后台。
围绕「同一套内容模型支撑多条业务线」设计，包含二手闲置、跑腿代办、技能互助、物品租赁、
失物招领、校园拼单六条业务线，以及教务（课表 / 成绩 / 考试）接入。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 小程序 `xiaojii-mp` | 原生微信小程序（自定义导航、无第三方 UI 框架） |
| 后端 `xiaojii-server` | Java 21 / Spring Boot 3.2.5 / MyBatis-Plus / MySQL 8 / Redis 7 / Redisson / JWT / Knife4j |
| 管理后台 `mall-admin-web` | Vue 3 / TypeScript / Vite 7 / Element Plus |
| 部署 `xiaojii-server/deploy` | Docker + Nginx + Shell 备份脚本 |

后端几个自研组件：

- `common/mq/RedisStreamMessageQueue` —— 基于 Redis Stream 的消息队列（消费组、pending 复查、死信）。
- `common/lock` —— 基于 Redisson 的分布式锁封装。
- `common/EduGpaCalculator` —— 教务绩点统一口径计算。
- `service/impl/EduAuthServiceImpl` —— 教务 SSO 登录（验证码 → ticket → 会话），凭证 AES-GCM 加密保存。

## 目录结构

```
xiaojii-mp/                微信小程序源码（pages / utils / assets）
xiaojii-server/            后端
  ├─ src/main/java/com/gdut/xiaojii/
  │    ├─ controller/app    用户端接口
  │    ├─ controller/admin  管理端接口
  │    ├─ service/impl      业务实现
  │    ├─ common            通用能力（MQ / 锁 / 异常 / 枚举 / 教务绩点）
  │    └─ config            配置类
  ├─ src/main/resources     application.yml 等配置
  ├─ sql/                   建表与迁移脚本
  ├─ fixtures/              教务模块本地样例数据（仅 local profile 使用）
  ├─ deploy/                Dockerfile / Nginx / 备份脚本 / 环境变量模板
  └─ docs/                  部署组件清单
mall-admin-web/            管理后台（Vue3 + Element Plus）
tools/                     调试与验证脚本（smoke / verify / 抓图）
design/, prototype/, admin-web/, wecollege-web/   设计稿与早期原型
验收截图/                   教务模块验收截图
校集-*.md                   设计与实施文档（方案、审计、学习手册等）
```

## 快速开始

后端：

```bash
cd xiaojii-server
# 1) 准备 MySQL 8 与 Redis 7
mysql -uroot -p < sql/schema.sql
mysql -uroot -p < sql/init_data.sql
# 2) 用环境变量注入连接信息（不要改 application.yml 里的默认值）
export DB_URL='jdbc:mysql://127.0.0.1:3306/xiaojii?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false'
export DB_USERNAME=root
export DB_PASSWORD='<你的密码>'
export REDIS_HOST=127.0.0.1
# 3) 启动（Windows 用 mvnw.cmd）
./mvnw spring-boot:run
```

接口地址 `http://localhost:8080/api`，接口文档 `http://localhost:8080/api/doc.html`。
若走本地联调 profile，把 `application-local.yml` 按 `application.yml` 的键自行补齐（该文件不入库）。

小程序：用微信开发者工具导入 `xiaojii-mp/`，填入自己的 AppID。
`utils/api.js` 按运行设备切换后端地址：开发者工具走 `http://localhost:8080/api`，
真机走线上域名。

管理后台：

```bash
cd mall-admin-web
npm install
npm run dev
```

## 数据与配置约定

- 数据库账号、Redis 密码、小程序 AppSecret、教务凭证密钥全部通过环境变量注入，
  仓库内只保留 `xiaojii-server/deploy/env.example` 模板。
- `application-local.yml`、`project.private.config.json`、`target/`、`node_modules/`、
  上传目录 `uploads/` 已写入 `.gitignore`，不随仓库分发。
- 表结构与增量变更见 `xiaojii-server/sql/`（`schema.sql` + `migration_*.sql`）。

## 文档

根目录 `校集-*.md` 记录了各阶段的方案与取舍，可作为代码背景阅读：

- `校集-上线部署记录-20261019.md` —— 部署结构与环境变量
- `校集-个人主页与教务接入方案-V2-20261019.md` —— 教务模块设计
- `校集-并发改造方案-20261112.md` / `校集-分布式锁-20261116.md` —— 并发与锁
- `校集-拼单与消息队列-20261116.md` —— Redis Stream 消息队列
- `校集后端-全量梳理清单-20261023.md` —— 后端类清单与协作关系
