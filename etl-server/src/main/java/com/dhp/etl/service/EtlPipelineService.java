package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlPipeline;

import java.util.Date;

public interface EtlPipelineService extends IBaseCrudService<EtlPipeline> {

    /**
     * 保存流程 (含唯一性与 SQL 校验)
     */
    boolean savePipeline(EtlPipeline pipeline);

    /**
     * 更新流程 (含唯一性与 SQL 校验)
     */
    boolean updatePipeline(EtlPipeline pipeline);

    /**
     * 启停定时调度
     */
    void toggleSchedule(Long id, Boolean enabled);

    /**
     * 手动触发一次运行
     */
    void triggerRun(Long id);

    /**
     * 终止运行中的流程
     */
    boolean cancelRunning(Long id);

    /**
     * 整体复制流程：抽取 / 转换 / 目标 / 告警配置原样带走，
     * 生成新编码与「（副本）」名称，调度默认停用，避免与原流程同时触发
     */
    EtlPipeline copyPipeline(Long id);

    /**
     * 仅更新最近一次运行状态与时间（定向两列更新，不受乐观锁版本变化影响，也不覆盖并发修改的配置列）
     */
    void updateLastRun(Long id, String lastRunStatus, Date lastRunTime);
}
