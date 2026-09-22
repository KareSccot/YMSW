-- Configure the language used for Task Template preview and delivery.
-- MySQL 8.x. Safe to run more than once. Existing templates keep the prior Chinese behavior.

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS rp_fix42_task_template_send_language$$
CREATE PROCEDURE rp_fix42_task_template_send_language()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cfg_task_template_header'
      AND COLUMN_NAME = 'send_language'
  ) THEN
    ALTER TABLE cfg_task_template_header
      ADD COLUMN send_language VARCHAR(8) NOT NULL DEFAULT 'ZH'
      COMMENT 'Token 与主数据下拉字段发送语言：ZH/EN'
      AFTER mode;
  END IF;
END$$

CALL rp_fix42_task_template_send_language()$$
DROP PROCEDURE rp_fix42_task_template_send_language$$

DELIMITER ;

UPDATE cfg_task_template_header
SET send_language = 'ZH'
WHERE send_language IS NULL
   OR TRIM(send_language) NOT IN ('ZH', 'EN');

