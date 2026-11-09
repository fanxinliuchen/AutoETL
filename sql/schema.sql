-- ====================================================================
-- DHP ETL 调度系统数据库初始化脚本
-- 适用数据库: MySQL 8.0+
-- 字符集: utf8mb4 / 排序规则: utf8mb4_0900_ai_ci
-- ====================================================================

CREATE DATABASE IF NOT EXISTS dhp_etl 
  CHARACTER SET utf8mb4 
  COLLATE utf8mb4_0900_ai_ci;

USE dhp_etl;

-- 1. ETL 目录分类表
DROP TABLE IF EXISTS t_etl_folder;
CREATE TABLE t_etl_folder (
    id               BIGINT       NOT NULL COMMENT '主键ID(雪花算法)',
    parent_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '父目录ID(0为根节点)',
    folder_name      VARCHAR(64)  NOT NULL COMMENT '目录名称',
    folder_sort      INT          NOT NULL DEFAULT 0 COMMENT '排序号',
    -- BaseTenantEntity 基础字段
    create_user_id   BIGINT       NULL     COMMENT '创建人ID',
    create_user_name VARCHAR(64)  NULL     COMMENT '创建人姓名',
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_user_id   BIGINT       NULL     COMMENT '更新人ID',
    update_user_name VARCHAR(64)  NULL     COMMENT '更新人姓名',
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    object_status_cd INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=已删除',
    object_version   INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    tenant_id        VARCHAR(32)  NULL     COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ETL流程目录表';

-- 2. 数据源连接配置表 (MySQL数据源与数仓目标)
DROP TABLE IF EXISTS t_etl_datasource;
CREATE TABLE t_etl_datasource (
    id               BIGINT       NOT NULL COMMENT '主键ID',
    ds_name          VARCHAR(64)  NOT NULL COMMENT '数据源名称',
    ds_type          VARCHAR(32)  NOT NULL COMMENT '类型: MYSQL / MYSQL_DW',
    driver_class     VARCHAR(128) NULL     DEFAULT 'com.mysql.cj.jdbc.Driver' COMMENT '驱动类',
    host             VARCHAR(128) NULL     COMMENT '主机IP/域名',
    port             INT          NULL     DEFAULT 3306 COMMENT '端口',
    database_name    VARCHAR(64)  NULL     COMMENT '数据库名称',
    encoding         VARCHAR(32)  NULL     DEFAULT 'UTF-8' COMMENT '字符编码',
    jdbc_url         VARCHAR(512) NOT NULL COMMENT 'JDBC连接串',
    username         VARCHAR(64)  NOT NULL COMMENT '用户名',
    password_cipher  VARCHAR(512) NOT NULL COMMENT '加密密码(Jasypt AES-256)',
    max_pool_size    INT          NOT NULL DEFAULT 5 COMMENT '连接池上限',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1=正常, 0=禁用',
    remark           VARCHAR(255) NULL     COMMENT '备注',
    -- BaseTenantEntity 基础字段
    create_user_id   BIGINT       NULL,
    create_user_name VARCHAR(64)  NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_user_id   BIGINT       NULL,
    update_user_name VARCHAR(64)  NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    object_status_cd INT          NOT NULL DEFAULT 0,
    object_version   INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(32)  NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ETL数据源配置表';

-- 3. ETL 流程配置主表
DROP TABLE IF EXISTS t_etl_pipeline;
CREATE TABLE t_etl_pipeline (
    id               BIGINT       NOT NULL COMMENT '主键ID',
    folder_id        BIGINT       NULL     COMMENT '所属目录ID',
    pipeline_code    VARCHAR(64)  NOT NULL COMMENT '流程编码(唯一标识)',
    pipeline_name    VARCHAR(128) NOT NULL COMMENT '流程名称',
    description      VARCHAR(500) NULL     COMMENT '流程描述',

    -- 输入源配置
    source_type      VARCHAR(32)  NOT NULL DEFAULT 'MYSQL' COMMENT '源类型: MYSQL / WEB_API',
    source_ds_id     BIGINT       NULL     COMMENT 'MySQL源: 数据源ID',
    source_query_sql TEXT         NULL     COMMENT 'MySQL源: 抽取SQL语句',
    api_url          VARCHAR(512) NULL     COMMENT 'WebAPI源: 请求URL',
    api_auth_type    VARCHAR(32)  NULL     DEFAULT 'NONE' COMMENT 'WebAPI鉴权类型: NONE/KINGDEE_K3/CUSTOM_LOGIN',
    api_auth_config  TEXT         NULL     COMMENT '前置认证/登录配置JSON',
    api_method       VARCHAR(16)  NULL     COMMENT 'WebAPI源: GET/POST',
    api_headers      JSON         NULL     COMMENT 'WebAPI源: 请求头JSON',
    api_body         TEXT         NULL     COMMENT 'WebAPI源: 请求体JSON/模板',
    api_data_path    VARCHAR(128) NULL     COMMENT 'WebAPI源: JsonPath结果路径 如 $.data.records',
    api_pagination   JSON         NULL     COMMENT 'WebAPI源: 分页配置JSON',

    -- 处理与输出
    transform_sql    MEDIUMTEXT   NULL     COMMENT 'SQL处理转换脚本(多段分号分隔)',
    target_ds_id     BIGINT       NULL     COMMENT '目标数仓数据源ID',
    target_table     VARCHAR(64)  NULL     COMMENT '目标表名',
    write_mode       VARCHAR(32)  NOT NULL DEFAULT 'APPEND' COMMENT '写入模式: APPEND/OVERWRITE/UPSERT',
    upsert_keys      VARCHAR(255) NULL     COMMENT 'UPSERT模式主键(逗号分隔)',
    field_meta       TEXT         NULL     COMMENT '抽取字段元数据JSON(中文名/类型/启用)',

    -- 调度与告警
    cron_expr        VARCHAR(64)  NULL     COMMENT 'Cron调度表达式',
    schedule_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '调度状态: 0=停用, 1=启用',
    timeout_seconds  INT          NOT NULL DEFAULT 1800 COMMENT '超时时间(秒)',
    max_retry_count  INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    alert_config_id  BIGINT       NULL     COMMENT '关联告警配置ID',

    -- 冗余状态(免连表快速查询)
    last_run_status  VARCHAR(32)  NULL     COMMENT '最近运行状态',
    last_run_time    DATETIME     NULL     COMMENT '最近运行时间',

    -- BaseTenantEntity 基础字段
    create_user_id   BIGINT       NULL,
    create_user_name VARCHAR(64)  NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_user_id   BIGINT       NULL,
    update_user_name VARCHAR(64)  NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    object_status_cd INT          NOT NULL DEFAULT 0,
    object_version   INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_folder_id (folder_id),
    KEY idx_pipeline_code (pipeline_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ETL作业流程配置表';

-- 4. 任务调度执行日志与实例表
DROP TABLE IF EXISTS t_etl_task_instance;
CREATE TABLE t_etl_task_instance (
    id               BIGINT       NOT NULL COMMENT '批次ID(雪花算法)',
    pipeline_id      BIGINT       NOT NULL COMMENT '流程ID',
    pipeline_code    VARCHAR(64)  NOT NULL COMMENT '流程编码(告警冗余)',
    pipeline_name    VARCHAR(128) NOT NULL COMMENT '流程名称(告警冗余)',
    trigger_type     VARCHAR(32)  NOT NULL COMMENT '触发类型: MANUAL / CRON',
    task_status      VARCHAR(32)  NOT NULL COMMENT '状态: RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELED',
    start_time       DATETIME     NOT NULL COMMENT '开始时间',
    end_time         DATETIME     NULL     COMMENT '结束时间',
    duration_ms      BIGINT       NULL     COMMENT '耗时(毫秒)',
    read_count       BIGINT       NOT NULL DEFAULT 0 COMMENT '抽取读取行数',
    write_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '写入影响行数',
    retry_count      INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    error_message    TEXT         NULL     COMMENT '异常摘要信息',
    log_detail       LONGTEXT     NULL     COMMENT '执行详细全量日志',
    -- BaseTenantEntity 基础字段
    create_user_id   BIGINT       NULL,
    create_user_name VARCHAR(64)  NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_user_id   BIGINT       NULL,
    update_user_name VARCHAR(64)  NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    object_status_cd INT          NOT NULL DEFAULT 0,
    object_version   INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_pipeline_time (pipeline_id, start_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ETL执行日志与实例表';

-- 5. 告警配置表
DROP TABLE IF EXISTS t_etl_alert_config;
CREATE TABLE t_etl_alert_config (
    id               BIGINT       NOT NULL COMMENT '主键ID',
    channel_name     VARCHAR(64)  NOT NULL COMMENT '通道名称',
    channel_type     VARCHAR(32)  NOT NULL COMMENT '通道类型: WECHAT_WORK / DINGTALK / FEISHU / EMAIL',
    webhook_url      VARCHAR(512) NULL     COMMENT 'Webhook通知地址',
    secret_token     VARCHAR(255) NULL     COMMENT '验签密钥(可选)',
    email_receivers  VARCHAR(512) NULL     COMMENT '邮件接收列表(逗号分隔)',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1=正常, 0=禁用',
    -- BaseTenantEntity 基础字段
    create_user_id   BIGINT       NULL,
    create_user_name VARCHAR(64)  NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_user_id   BIGINT       NULL,
    update_user_name VARCHAR(64)  NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    object_status_cd INT          NOT NULL DEFAULT 0,
    object_version   INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(32)  NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ETL告警配置表';

-- 6. 金蝶登录凭证表 (密码 AES 加密存储，接口不回显)
DROP TABLE IF EXISTS t_etl_kingdee_credential;
CREATE TABLE t_etl_kingdee_credential (
    id               BIGINT       NOT NULL COMMENT '主键ID',
    cred_name        VARCHAR(64)  NULL     DEFAULT '默认凭证' COMMENT '凭证名称',
    login_url        VARCHAR(512) NOT NULL COMMENT '金蝶登录接口地址',
    acct_id          VARCHAR(64)  NULL     COMMENT '数据中心/账套代码 acctID',
    user_name        VARCHAR(64)  NULL     COMMENT '登录用户名',
    password_cipher  VARCHAR(512) NOT NULL COMMENT '加密密码(AES)',
    lcid             INT          NOT NULL DEFAULT 2052 COMMENT '多语言环境代码',
    is_default       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否默认凭证: 1=是',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1=正常, 0=禁用',
    create_user_id   BIGINT       NULL,
    create_user_name VARCHAR(64)  NULL,
    create_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_user_id   BIGINT       NULL,
    update_user_name VARCHAR(64)  NULL,
    update_time      DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    object_status_cd INT          NOT NULL DEFAULT 0,
    object_version   INT          NOT NULL DEFAULT 1,
    tenant_id        VARCHAR(32)  NULL,
    PRIMARY KEY (id),
    KEY idx_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='金蝶登录凭证表';

-- 7. 初始化示例演示数据
INSERT INTO t_etl_folder (id, parent_id, folder_name, folder_sort) VALUES
(1001, 0, 'ERP系统对接', 1),
(1002, 0, 'CRM客户中心', 2),
(1003, 1001, '采购与物料', 1),
(1004, 1001, '财务核算', 2);
