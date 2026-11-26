-- 2026-10-19 教务账号加密凭据（不存明文）
CREATE TABLE IF NOT EXISTS `edu_credential` (
  `user_id`       BIGINT       NOT NULL COMMENT '用户 ID，与 user.id 1:1',
  `payload_cipher` TEXT        NOT NULL COMMENT 'AES-GCM 密文（account + password 的 JSON）',
  `nonce`         VARCHAR(32)  NOT NULL COMMENT 'GCM nonce（base64）',
  `key_version`   INT          NOT NULL DEFAULT 1 COMMENT '密钥版本，便于轮换',
  `expires_at`    DATETIME     NOT NULL COMMENT '凭据过期时间（30 天滑动）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教务账号加密凭据';
