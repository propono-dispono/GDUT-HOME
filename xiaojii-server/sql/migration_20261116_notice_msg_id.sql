-- 2026-11-16 通知幂等：给 notice 加 msg_id（消息 ID）
--
-- 【为什么需要这一列】
-- Redis Stream 的投递语义是「至少一次」：消费者处理成功但 ACK 前进程挂了，
-- 这条消息会被重新投递 → 通知表插两条一模一样的 → 用户看到两条重复通知。
-- 解决方式：把消息 ID 存下来并建唯一索引，重复消费时数据库直接拒绝第二条。
--
-- 【为什么 msg_id 可为 NULL】
-- 项目里还有 8 处是同步直接调用 NoticeService.push 的（没走 MQ），
-- 它们没有消息 ID，这一列留空即可。MySQL 的唯一索引允许多个 NULL 并存，
-- 所以老数据和同步调用都不会互相冲突。
--
-- 【为什么要用动态 SQL 包一层】
-- MySQL 8 没有 ADD COLUMN IF NOT EXISTS，直接 ALTER 第二次执行会报 1060。
-- 用 information_schema 先判断再执行，保证这份脚本可以重复跑。

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notice' AND COLUMN_NAME = 'msg_id'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `notice` ADD COLUMN `msg_id` VARCHAR(64) DEFAULT NULL COMMENT ''MQ 消息 ID，用于消费幂等；同步写入的通知为 NULL''',
  'SELECT ''msg_id 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notice' AND INDEX_NAME = 'uk_msg_id'
);
SET @ddl2 := IF(@idx_exists = 0,
  'ALTER TABLE `notice` ADD UNIQUE KEY `uk_msg_id` (`msg_id`) COMMENT ''同一条消息只能落一条通知''',
  'SELECT ''uk_msg_id 已存在，跳过'' AS idx');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
