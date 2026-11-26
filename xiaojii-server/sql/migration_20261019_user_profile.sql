-- 2026-10-19 个人资料扩展（与 user 1:1）
-- 说明：只放展示型资料，不影响登录/信用/认证核心字段
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
