package com.dhp.etl.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dhp.common.entity.BaseTenantEntity;
import com.dhp.etl.enums.TaskStatusEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 任务运行实例实体 (规范 03 §4.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_etl_task_instance")
public class EtlTaskInstance extends BaseTenantEntity {

    /** 流程ID */
    private Long pipelineId;

    /** 流程编码(冗余) */
    private String pipelineCode;

    /** 流程名称(冗余) */
    private String pipelineName;

    /** 触发类型: MANUAL / CRON */
    private TriggerTypeEnum triggerType;

    /** 状态: RUNNING / SUCCESS / FAILED / TIMEOUT / CANCELED */
    private TaskStatusEnum taskStatus;

    /** 开始时间 */
    private Date startTime;

    /** 结束时间 */
    private Date endTime;

    /** 耗时(毫秒) */
    private Long durationMs;

    /** 读取行数 */
    private Long readCount;

    /** 写入行数 */
    private Long writeCount;

    /** 已重试次数 */
    private Integer retryCount;

    /** 异常摘要 */
    private String errorMessage;

    /** 详细执行日志 */
    private String logDetail;
}
