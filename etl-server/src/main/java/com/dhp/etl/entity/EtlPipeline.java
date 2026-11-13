package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.enums.WriteModeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * ETL 流程实体 (规范 03 §4.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_pipeline")
public class EtlPipeline extends BaseTenantEntity {

    /** 所属目录ID */
    private Long folderId;

    /** 流程编码 */
    private String pipelineCode;

    /** 流程名称 */
    private String pipelineName;

    /** 流程描述 */
    private String description;

    // ===== 输入源配置 =====
    /** 源类型: MYSQL / WEB_API */
    private SourceTypeEnum sourceType;

    /** MySQL源: 数据源ID */
    private Long sourceDsId;

    /** MySQL源: 抽取 SQL */
    private String sourceQuerySql;

    /** WebAPI源: 请求 URL */
    private String apiUrl;

    /** WebAPI源: 前置鉴权类型: NONE / KINGDEE / CUSTOM */
    private String apiAuthType;

    /** WebAPI源: 前置鉴权/登录配置 JSON */
    private String apiAuthConfig;

    /** WebAPI源: GET/POST */
    private String apiMethod;

    /** WebAPI源: 请求头 JSON */
    private String apiHeaders;

    /** WebAPI源: 请求体模板 */
    private String apiBody;

    /** 过滤条件结构化配置 JSON（多字段多条件，用于生成 FilterString） */
    private String filterMeta;

    /** WebAPI源: JsonPath 结果路径 如 $.data.records */
    private String apiDataPath;

    /** WebAPI源: 分页配置 JSON */
    private String apiPagination;

    // ===== 处理与输出 =====
    /** SQL 转换脚本 */
    private String transformSql;

    /** 目标数仓数据源ID */
    private Long targetDsId;

    /** 目标表名 */
    private String targetTable;

    /** 写入模式: APPEND / OVERWRITE / UPSERT / SYNC */
    private WriteModeEnum writeMode;

    // 原 upsertKeys（UPSERT 业务主键 / SYNC 比对主键）已废弃：
    // 两种模式都改为读取目标表真实主键，不再从流程配置里取。
    // 库中的 t_etl_pipeline.upsert_keys 列保留（可空），仅为不破坏历史数据。

    /** 增量同步配置 JSON（范围字段 / 窗口天数 / 软删除列 / 删除比例上限） */
    private String syncConfig;

    /** 抽取字段清单元数据 JSON（中文名/类型/启用；字段名与顺序以 apiBody.FieldKeys 为准） */
    private String fieldMeta;

    // ===== 调度与告警 =====
    /** Cron 表达式 */
    private String cronExpr;

    /** 调度状态: 0=停用, 1=启用 */
    private Integer scheduleStatus;

    /** 超时时间(秒) */
    private Integer timeoutSeconds;

    /** 失败最大重试次数 */
    private Integer maxRetryCount;

    /** 关联告警配置ID */
    private Long alertConfigId;

    // ===== 冗余字段 =====
    /** 最近运行状态 */
    private String lastRunStatus;

    /** 最近运行时间 */
    private Date lastRunTime;
}
