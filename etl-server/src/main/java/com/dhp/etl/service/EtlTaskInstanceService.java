package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.enums.TriggerTypeEnum;

public interface EtlTaskInstanceService extends IBaseCrudService<EtlTaskInstance> {

    /**
     * 创建一个处于运行中的新任务实例
     */
    EtlTaskInstance createRunningInstance(EtlPipeline pipeline, TriggerTypeEnum triggerType);
}
