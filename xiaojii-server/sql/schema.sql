-- ============================================================
-- 校集 · 广东工业大学校园互助平台 —— 建表脚本（MySQL 8.0+, InnoDB, utf8mb4）
--
-- 使用方式：
--   mysql -h <host> -u <user> -p -e "CREATE DATABASE IF NOT EXISTS xiaojii DEFAULT CHARSET utf8mb4;"
--   mysql -h <host> -u <user> -p xiaojii < schema.sql
--
-- 设计约定（重要，改表前先读）：
--   1. 不建外键：逻辑删除 + 历史订单要长期保留，外键只会让删除和迁移变难，
--      引用完整性由 Service 层保证。
--   2. 金额一律 DECIMAL(10,2)，只做记录，平台不经手资金（合规红线）。
--   3. 时间统一 DATETIME（不用 TIMESTAMP，避免 2038 问题与时区坑）。
--   4. order 是 MySQL 保留字，所有 SQL 必须写 `order`（反引号）。
--   5. 状态类字段用 TINYINT（帖子/审核）或 VARCHAR(16)（订单，便于日志可读）。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 1. 用户
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid`        VARCHAR(64)  NOT NULL COMMENT '微信 openid，登录唯一标识',
  `unionid`       VARCHAR(64)  DEFAULT NULL COMMENT '微信 unionid，同一开放平台下打通多应用',
  `nickname`      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '昵称',
  `avatar`        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像 URL',
  `intro`         VARCHAR(200) NOT NULL DEFAULT '' COMMENT '个人简介',
  `credit_score`  INT          NOT NULL DEFAULT 80 COMMENT '信用分 0-100，初始 80（留出上升空间）',
  `verify_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '认证状态：0未认证 1待审核 2已认证 3驳回',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1正常 2禁言 3封禁',
  `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后登录时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_status_credit` (`status`, `credit_score`),
  KEY `idx_verify_status` (`verify_status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ------------------------------------------------------------
-- 2. 校园身份认证申请
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_verify`;
CREATE TABLE IF NOT EXISTS `user_profile` (
  `user_id`        BIGINT       NOT NULL COMMENT '用户 ID，与 user.id 1:1',
  `gender`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女 3保密',
  `birthday`       DATE         DEFAULT NULL COMMENT '生日，仅用于计算年龄，不公开',
  `major`          VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '专业',
  `grade`          SMALLINT     DEFAULT NULL COMMENT '入学年份，如 2023',
  `college`        VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '学院',
  `campus`         VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '校区',
  `tags`           JSON         DEFAULT NULL COMMENT '爱好/技能标签，最多 6 个',
  `available_time` VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '可互助时间',
  `looking_for`    JSON         DEFAULT NULL COMMENT '想找的类型',
  `privacy`        JSON         DEFAULT NULL COMMENT '逐项可见性：{major:true,...}',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户扩展资料（展示型）';

CREATE TABLE `user_verify` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL COMMENT '申请人',
  `student_no`    VARCHAR(32)  NOT NULL COMMENT '学号，全局唯一，防一人多号',
  `real_name`     VARCHAR(32)  NOT NULL COMMENT '真实姓名',
  `card_image`    VARCHAR(255) NOT NULL COMMENT '校园卡照片（本地上传目录的相对 key）',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0待审 1通过 2驳回（注意与 user.verify_status 的 2=已认证 相反）',
  `reject_reason` VARCHAR(100) DEFAULT NULL COMMENT '驳回原因',
  `reviewer_id`   BIGINT       DEFAULT NULL COMMENT '审核人 admin_user.id',
  `reviewed_at`   DATETIME     DEFAULT NULL COMMENT '审核时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_no` (`student_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园认证申请';

-- ------------------------------------------------------------
-- 3. 帖子主表（6 条业务线共用，靠 biz_type 区分）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `post`;
CREATE TABLE `post` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT        NOT NULL COMMENT '发布人',
  `biz_type`      VARCHAR(16)   NOT NULL COMMENT 'SECONDHAND/ERRAND/SKILL/RENTAL/LOST/GROUPBUY',
  `category_id`   INT           NOT NULL DEFAULT 0 COMMENT '二级分类，见 CategoryEnum',
  `title`         VARCHAR(60)   NOT NULL COMMENT '标题',
  `content`       TEXT          NOT NULL COMMENT '正文',
  `price`         DECIMAL(10,2) DEFAULT NULL COMMENT '二手售价/跑腿酬金/技能报价/日租金/拼单人均，仅记录',
  `negotiable`    TINYINT       NOT NULL DEFAULT 0 COMMENT '是否可议价：0否 1是',
  `images`        JSON          DEFAULT NULL COMMENT '图片 key 数组，最多 9 张',
  `building`      VARCHAR(32)   NOT NULL DEFAULT '' COMMENT '楼栋位置，如 东区宿舍',
  `tags`          VARCHAR(120)  NOT NULL DEFAULT '' COMMENT '逗号分隔的标签',
  `status`        TINYINT       NOT NULL DEFAULT 0 COMMENT '业务状态：0待审 1展示中 2已下架 3已成交 4已关闭',
  `audit_status`  TINYINT       NOT NULL DEFAULT 0 COMMENT '审核状态：0待审 1机器通过 2人工通过 3驳回',
  `reject_reason` VARCHAR(100)  DEFAULT NULL COMMENT '驳回原因',
  `view_count`    INT           NOT NULL DEFAULT 0 COMMENT '浏览量',
  `expire_at`     DATETIME      DEFAULT NULL COMMENT '跑腿截止 / 拼单截止，到期自动关闭',
  `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_feed` (`biz_type`, `status`, `deleted`, `created_at`),
  KEY `idx_user` (`user_id`, `status`),
  KEY `idx_audit` (`audit_status`, `created_at`) COMMENT '后台待审列表按时间取，避免全表扫',
  KEY `idx_expire` (`status`, `expire_at`) COMMENT '定时任务扫描过期帖子',
  KEY `idx_title` (`title`(20)) COMMENT 'LIKE 前缀搜索用，本项目不上 ES',
  KEY `idx_price` (`price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子（统一发布表）';

-- ------------------------------------------------------------
-- 4-5. 跑腿 / 失物 扩展表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `post_errand`;
CREATE TABLE `post_errand` (
  `post_id`      BIGINT       NOT NULL COMMENT '与 post.id 一对一',
  `pickup_addr`  VARCHAR(100) NOT NULL COMMENT '取件地',
  `deliver_addr` VARCHAR(100) NOT NULL COMMENT '送达地',
  `deadline`     DATETIME     NOT NULL COMMENT '最晚送达时间',
  `advance_pay`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否需垫付：0否 1是',
  PRIMARY KEY (`post_id`),
  KEY `idx_deadline` (`deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跑腿扩展';

DROP TABLE IF EXISTS `post_lost`;
CREATE TABLE `post_lost` (
  `post_id`    BIGINT       NOT NULL,
  `lost_type`  TINYINT      NOT NULL COMMENT '1寻物启事 2拾物招领',
  `item_name`  VARCHAR(60)  NOT NULL COMMENT '物品名称',
  `lost_place` VARCHAR(100) NOT NULL COMMENT '丢失/拾取地点',
  `lost_time`  DATETIME     DEFAULT NULL COMMENT '丢失/拾取时间',
  PRIMARY KEY (`post_id`),
  KEY `idx_type` (`lost_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='失物招领扩展';

-- ------------------------------------------------------------
-- 6-8. 技能 / 租赁 / 拼单 扩展表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `post_skill`;
CREATE TABLE `post_skill` (
  `post_id`       BIGINT      NOT NULL,
  `service_type`  VARCHAR(32) NOT NULL COMMENT '技能类目，如 PPT美化/修图剪辑',
  `delivery_days` INT         NOT NULL DEFAULT 1 COMMENT '预计交付天数',
  `service_mode`  VARCHAR(16) NOT NULL DEFAULT '线上' COMMENT '服务方式：线上/线下',
  `duration_desc` VARCHAR(32) NOT NULL DEFAULT '' COMMENT '单次时长描述',
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能互助扩展';

DROP TABLE IF EXISTS `post_rent`;
CREATE TABLE `post_rent` (
  `post_id`        BIGINT        NOT NULL,
  `rent_price`     DECIMAL(10,2) NOT NULL COMMENT '租金，仅记录',
  `rent_unit`      VARCHAR(8)    NOT NULL DEFAULT '天' COMMENT '计价单位：天/周',
  `deposit`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '押金（线下自行协商，平台不代收）',
  `min_days`       INT           NOT NULL DEFAULT 1 COMMENT '起租天数',
  `available_from` DATETIME      DEFAULT NULL COMMENT '可租起始时间',
  PRIMARY KEY (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物品租赁扩展';

DROP TABLE IF EXISTS `post_groupbuy`;
CREATE TABLE `post_groupbuy` (
  `post_id`       BIGINT      NOT NULL,
  `target_count`  INT         NOT NULL COMMENT '目标人数（含发起人）',
  `current_count` INT         NOT NULL DEFAULT 1 COMMENT '当前人数（发起人计入，初始 1）',
  `deadline`      DATETIME    NOT NULL COMMENT '拼单截止时间',
  `with_errand`   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否需要代取：0否 1是',
  `pickup_point`  VARCHAR(100) NOT NULL DEFAULT '' COMMENT '自提点',
  PRIMARY KEY (`post_id`),
  KEY `idx_deadline` (`deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园拼单扩展（不走订单）';

DROP TABLE IF EXISTS `groupbuy_member`;
CREATE TABLE `groupbuy_member` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `post_id`    BIGINT      NOT NULL,
  `user_id`    BIGINT      NOT NULL,
  `status`     VARCHAR(16) NOT NULL DEFAULT 'JOINED' COMMENT 'JOINED/CANCELLED',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_user` (`post_id`, `user_id`) COMMENT '同一人同一单只能有一条记录，退出后改状态',
  KEY `idx_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼单成员（不走订单表）';

-- ------------------------------------------------------------
-- 9. 订单（order 是保留字，必须反引号）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `order`;
CREATE TABLE `order` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `order_no`      VARCHAR(32)   NOT NULL COMMENT '业务单号 yyyyMMdd + 6 位随机',
  `biz_type`      VARCHAR(16)   NOT NULL COMMENT '冗余自帖子，便于按业务线统计',
  `post_id`       BIGINT        NOT NULL,
  `buyer_id`      BIGINT        NOT NULL COMMENT '买家/需求方/租客/发单人',
  `seller_id`     BIGINT        NOT NULL COMMENT '卖家/服务方/出租方/接单人',
  `amount`        DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '金额（仅记录，线下自行结算）',
  `deposit`       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '押金（租赁用，线下收取，平台不代管）',
  `status`        VARCHAR(16)   NOT NULL DEFAULT 'CREATED' COMMENT '8 状态：CREATED/CONFIRMED/IN_PROGRESS/DELIVERED/COMPLETED/REVIEWED/CANCELLED/DISPUTE',
  `cancel_reason` VARCHAR(200)  DEFAULT NULL COMMENT '取消/仲裁原因',
  `finish_at`     DATETIME      DEFAULT NULL COMMENT '进入终态的时间',
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_buyer` (`buyer_id`, `status`),
  KEY `idx_seller` (`seller_id`, `status`),
  KEY `idx_post` (`post_id`),
  KEY `idx_status_time` (`status`, `created_at`) COMMENT '定时任务扫描超时单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单（状态存字符串，便于日志与排查）';

-- ------------------------------------------------------------
-- 10. 订单状态流转日志（纠纷仲裁的唯一证据，必写）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `order_log`;
CREATE TABLE `order_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`      BIGINT       NOT NULL,
  `from_status`   VARCHAR(16)  DEFAULT NULL COMMENT '变更前状态，首条日志为空',
  `to_status`     VARCHAR(16)  NOT NULL COMMENT '变更后状态',
  `operator_id`   BIGINT       DEFAULT NULL COMMENT '操作人；系统任务/平台仲裁时可空',
  `operator_role` VARCHAR(16)  DEFAULT NULL COMMENT 'BUYER/SELLER/PLATFORM',
  `remark`        VARCHAR(200) DEFAULT NULL COMMENT '取消原因、仲裁结论等',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单流转日志';

-- ------------------------------------------------------------
-- 11-12. 私信
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_a`     BIGINT       NOT NULL COMMENT '恒为较小的 user_id',
  `user_b`     BIGINT       NOT NULL COMMENT '恒为较大的 user_id',
  `ref_type`   VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT '关联对象类型：POST/ORDER/NONE（必须非空，否则唯一键失效）',
  `ref_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '关联对象 ID，无关联填 0',
  `last_msg`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后一条消息摘要',
  `last_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后消息时间，用于会话列表排序',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_ref` (`user_a`, `user_b`, `ref_type`, `ref_id`) COMMENT '同一对用户 + 同一对象只有一条会话（原文误写成 KEY，已修正）',
  KEY `idx_a` (`user_a`, `last_at`),
  KEY `idx_b` (`user_b`, `last_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信会话';

DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id`         BIGINT        NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT        NOT NULL,
  `from_user`  BIGINT        NOT NULL COMMENT '发送者',
  `msg_type`   TINYINT       NOT NULL DEFAULT 1 COMMENT '1文字 2图片 3订单卡片',
  `content`    VARCHAR(1000) NOT NULL COMMENT '文字内容 或 图片 key 或 订单号',
  `read_flag`  TINYINT       NOT NULL DEFAULT 0 COMMENT '接收方是否已读',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`, `id`),
  KEY `idx_unread` (`session_id`, `read_flag`, `id`) COMMENT '未读数聚合，避免全表扫'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='私信消息';

-- ------------------------------------------------------------
-- 13. 评价
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `review`;
CREATE TABLE `review` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `order_id`     BIGINT       NOT NULL,
  `from_user_id` BIGINT       NOT NULL COMMENT '评价人',
  `to_user_id`   BIGINT       NOT NULL COMMENT '被评价人',
  `score`        TINYINT      NOT NULL COMMENT '1-5 星',
  `content`      VARCHAR(300) NOT NULL DEFAULT '' COMMENT '评价内容',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_from` (`order_id`, `from_user_id`) COMMENT '同一订单同一人只能评一次',
  KEY `idx_to_user` (`to_user_id`, `created_at`),
  CONSTRAINT `ck_review_score` CHECK (`score` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价';

-- ------------------------------------------------------------
-- 14. 信用分流水
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `credit_log`;
CREATE TABLE `credit_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT       NOT NULL,
  `delta`       INT          NOT NULL COMMENT '变化值，可为负',
  `reason_type` VARCHAR(32)  NOT NULL COMMENT 'ORDER_DONE/REVIEW/CANCEL/ADMIN/REPORT',
  `ref_id`      BIGINT       DEFAULT NULL COMMENT '关联订单/评价 ID',
  `remark`      VARCHAR(100) NOT NULL DEFAULT '' COMMENT '说明，展示在信用分明细里',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分流水';

-- ------------------------------------------------------------
-- 15. 举报
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `report`;
CREATE TABLE `report` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `target_type` VARCHAR(16)  NOT NULL COMMENT 'POST/USER/MSG',
  `target_id`   BIGINT       NOT NULL,
  `reporter_id` BIGINT       NOT NULL,
  `reason_type` VARCHAR(32)  NOT NULL COMMENT '广告/诈骗/违禁/辱骂/其他',
  `description` VARCHAR(300) NOT NULL DEFAULT '' COMMENT '补充说明（字段名不用 desc，是 MySQL 保留字）',
  `images`      JSON         DEFAULT NULL COMMENT '证据截图 key 数组',
  `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理 2不成立',
  `handler_id`  BIGINT       DEFAULT NULL COMMENT '处理人 admin_user.id',
  `handle_remark` VARCHAR(200) DEFAULT NULL COMMENT '处理结论',
  `handled_at`  DATETIME     DEFAULT NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reporter_target` (`reporter_id`, `target_type`, `target_id`) COMMENT '同一人对同一对象只能举报一次',
  KEY `idx_status` (`status`, `created_at`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报';

-- ------------------------------------------------------------
-- 16. 敏感词
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sensitive_word`;
CREATE TABLE `sensitive_word` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `word`       VARCHAR(64)  NOT NULL COMMENT '词条；组合词用 + 连接，如 代取+外卖 表示同时命中才拦',
  `category`   VARCHAR(32)  NOT NULL DEFAULT 'OTHER' COMMENT '禁售/违禁/辱骂/其他',
  `level`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1拦截 2警告',
  `enabled`    TINYINT      NOT NULL DEFAULT 1,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`),
  KEY `idx_enabled` (`enabled`, `level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词库';

-- ------------------------------------------------------------
-- 17. 审核记录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `audit_record`;
CREATE TABLE `audit_record` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `target_type`    VARCHAR(16)  NOT NULL COMMENT 'POST/VERIFY',
  `target_id`      BIGINT       NOT NULL,
  `machine_result` VARCHAR(16)  DEFAULT NULL COMMENT '机器审核结果：PASS/REJECT/UNKNOWN',
  `hit_words`      VARCHAR(255) DEFAULT NULL COMMENT '命中的敏感词，便于复核',
  `reviewer_id`    BIGINT       DEFAULT NULL COMMENT '人工审核人',
  `result`         VARCHAR(16)  NOT NULL COMMENT '最终结果：PASS/REJECT',
  `remark`         VARCHAR(200) DEFAULT NULL,
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`, `target_id`),
  KEY `idx_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核记录';

-- ------------------------------------------------------------
-- 18. 公告
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `announcement`;
CREATE TABLE `announcement` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `title`      VARCHAR(60)  NOT NULL,
  `content`    TEXT         NOT NULL,
  `type`       VARCHAR(16)  NOT NULL DEFAULT 'POPUP' COMMENT 'POPUP弹窗 / INNER站内',
  `status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '0下线 1上线',
  `publish_at` DATETIME     DEFAULT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_time` (`status`, `publish_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统公告';

-- ------------------------------------------------------------
-- 19. 站内通知（设计文档漏了这张表，但接口 /app/notice/* 已存在）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `notice`;
CREATE TABLE `notice` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT       NOT NULL COMMENT '接收人',
  `type`       VARCHAR(16)  NOT NULL COMMENT 'SYSTEM/AUDIT/ORDER/CHAT/REPORT/GROUPBUY',
  `title`      VARCHAR(60)  NOT NULL DEFAULT '',
  `content`    VARCHAR(300) NOT NULL DEFAULT '',
  `ref_type`   VARCHAR(16)  DEFAULT NULL COMMENT '跳转对象类型：POST/ORDER/SESSION',
  `ref_id`     BIGINT       DEFAULT NULL COMMENT '跳转对象 ID',
  `read_flag`  TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_unread` (`user_id`, `read_flag`, `id`),
  KEY `idx_user_type` (`user_id`, `type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知';

-- ------------------------------------------------------------
-- 20. 收藏
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `favorite`;
CREATE TABLE `favorite` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT   NOT NULL,
  `post_id`    BIGINT   NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`, `post_id`),
  KEY `idx_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收藏';

-- ------------------------------------------------------------
-- 21. 管理员（与 user 完全独立的主体）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `admin_user`;
CREATE TABLE `admin_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `username`      VARCHAR(32)  NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL COMMENT 'PBKDF2-HMAC-SHA256，格式 pbkdf2$iter$salt$hash',
  `nickname`      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '显示名',
  `role`          VARCHAR(16)  NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN 普通管理员 / SUPER 超级管理员',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `last_login_at` DATETIME     DEFAULT NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台管理员';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 表清单（共 22 张）
--   user, user_verify, post, post_errand, post_lost, post_skill, post_rent,
--   post_groupbuy, groupbuy_member, `order`, order_log, chat_session, chat_message,
--   review, credit_log, report, sensitive_word, audit_record, announcement,
--   notice, favorite, admin_user
-- ============================================================
