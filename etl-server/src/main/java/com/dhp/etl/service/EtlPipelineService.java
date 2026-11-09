package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlPipeline;

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
}
