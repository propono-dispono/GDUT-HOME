-- 2026-11-15 跑腿接单：接单记录表（方案 B）
-- 设计依据：校集-跑腿接单功能设计-20261115.md 第 4 节
--
-- 一句话：这张表记录「这一单被谁接了、走到哪一步、什么时候结束的」，
-- 而 post.status 只回答「别人还能不能接」——两者职责分开，互不越界。

-- 【写法】IF NOT EXISTS，可重复执行
-- 【为什么】这份脚本会在本地库和生产库各跑一次，重复跑不能报错
CREATE TABLE IF NOT EXISTS `errand_take` (

  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',

  `post_id`       BIGINT       NOT NULL COMMENT '跑腿帖 id，对应 post.id',

  `taker_id`      BIGINT       NOT NULL COMMENT '接单人 user_id',

  -- 状态取值见 common/enums/ErrandTakeStatus.java：
  --   IN_PROGRESS 已接单未取件 / PICKED 已取件 / DELIVERED 已送达
  --   COMPLETED 已完成 / CANCELLED 已取消 / EXPIRED 已超时被系统释放
  --
  -- 【为什么用 VARCHAR 而不是 TINYINT 存状态码】
  -- 这是"给人看"的运维字段：出了脏数据直接在 MySQL 里 SELECT 就能看懂，
  -- 不用回查 Java 常量表。代价是比 TINYINT 多占几个字节，本项目量级完全无关。
  `status`        VARCHAR(16)  NOT NULL COMMENT 'IN_PROGRESS/PICKED/DELIVERED/COMPLETED/CANCELLED/EXPIRED',

  -- 【为什么单独记这几个时间，而不是只留 created_at/updated_at】
  -- 超时释放要以「接单时刻」为基准算 30 分钟，不能用 updated_at（它会被任何更新刷新）；
  -- 取件/送达时刻是履约证据，将来做纠纷仲裁时是唯一凭据。
  `taken_at`      DATETIME     NOT NULL COMMENT '接单时刻，超时释放以它为基准',
  `picked_at`     DATETIME     DEFAULT NULL COMMENT '确认取件时刻',
  `delivered_at`  DATETIME     DEFAULT NULL COMMENT '标记送达时刻',
  `completed_at`  DATETIME     DEFAULT NULL COMMENT '发布者确认完成时刻',
  `closed_at`     DATETIME     DEFAULT NULL COMMENT '终态时刻（取消/超时），与 completed_at 互斥',

  `cancel_reason` VARCHAR(64)  DEFAULT NULL COMMENT '取消原因：TAKER_CANCEL/OWNER_REVOKE/ADMIN_FORCE/TIMEOUT',

  -- 信用分变化。0 表示本单没有奖惩。
  -- 单独存一列而不是事后去信用分流水里找，是为了"这一单到底扣没扣分"一眼可见
  `credit_delta`  INT          NOT NULL DEFAULT 0 COMMENT '本单产生的信用分变化（负数=扣分）',

  -- ★ 这张表最关键的列
  --
  -- 【写法】进行中时写 post_id，进入终态时置 NULL，并给它建唯一索引
  -- 【为什么必须这样】
  --   业务不变量是「一个帖子在任一时刻只能有一条进行中的接单记录」。
  --   MySQL 没有「部分唯一索引」（PostgreSQL 有 ... WHERE status='IN_PROGRESS'），
  --   所以在 MySQL 里的标准做法就是：用一个可空列表达"是否占用"，
  --   占用时填业务键、释放时置 NULL —— 因为唯一索引允许多个 NULL 共存，
  --   终态可以无限多条，进行中只能有一条。
  -- 【它挡的是哪个并发场景】
  --   S9「帖子状态与接单记录不一致」：post.status 的 CAS 已经挡住了绝大多数重复接单，
  --   但万一有人绕过 Service 直接写库、或将来加了"改派"逻辑漏了判断，
  --   这一列会让数据库直接拒绝第二条进行中记录，而不是留下脏数据。
  -- 【代价】每次状态跃迁都必须记得维护它（置 NULL 或保持），漏了就变成"这单永远接不了"。
  --   → 所以 Service 里所有终态更新都统一走 updateStatusAndRelease() 一个方法，不散着写。
  `active_post_id` BIGINT      DEFAULT NULL COMMENT '进行中时=post_id，终态置 NULL；配合唯一索引保证一单只有一条进行中记录',

  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),

  -- 【为什么是 UNIQUE 而不是普通索引】见上面 active_post_id 的说明
  UNIQUE KEY `uk_active_post` (`active_post_id`) COMMENT '一单同时只能有一条进行中记录；NULL 不参与唯一性',

  -- 查"这一单的历史接单记录"（含取消过的、超时的）
  KEY `idx_post_status` (`post_id`, `status`),

  -- 查"我接过的单"，以及将来算"我手上有几单没完成"
  KEY `idx_taker_status` (`taker_id`, `status`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跑腿接单记录（一单可有多条历史，但进行中只能一条）';
