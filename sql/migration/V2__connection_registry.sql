-- ============================================================
-- V2: 连接注册中心
--   1) t_etl_datasource 升级为「连接」：加 conn_type + config_cipher
--   2) 非 MySQL 连接没有 JDBC 三件套，必须放开 NOT NULL
-- 幂等：可重复执行
-- ============================================================

SET @db := DATABASE();

-- conn_type
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_etl_datasource' AND COLUMN_NAME = 'conn_type') = 0,
    'ALTER TABLE t_etl_datasource ADD COLUMN conn_type VARCHAR(32) NOT NULL DEFAULT ''MYSQL'' COMMENT ''连接类型: MYSQL / KINGDEE_K3 / IYUNBIAO'' AFTER ds_name',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- config_cipher
SET @sql := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_etl_datasource' AND COLUMN_NAME = 'config_cipher') = 0,
    'ALTER TABLE t_etl_datasource ADD COLUMN config_cipher TEXT NULL COMMENT ''非 MySQL 连接配置(JSON, AES 加密)'' AFTER password_cipher',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 放开 JDBC 专属列的 NOT NULL（云表/金蝶行填不进去）
ALTER TABLE t_etl_datasource
    MODIFY COLUMN jdbc_url        VARCHAR(512) NULL COMMENT 'JDBC连接串(仅 MYSQL 必填)',
    MODIFY COLUMN username        VARCHAR(64)  NULL COMMENT '用户名(仅 MYSQL 必填)',
    MODIFY COLUMN password_cipher VARCHAR(512) NULL COMMENT '加密密码(仅 MYSQL 必填)';

-- 存量数据补默认类型
UPDATE t_etl_datasource SET conn_type = 'MYSQL' WHERE conn_type IS NULL OR conn_type = '';
