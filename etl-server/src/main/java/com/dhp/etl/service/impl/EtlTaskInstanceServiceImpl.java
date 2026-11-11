package com.dhp.etl.service.impl;

import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.enums.TaskStatusEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.mapper.EtlTaskInstanceMapper;
import com.dhp.etl.service.EtlTaskInstanceService;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class EtlTaskInstanceServiceImpl extends BaseCrudServiceImpl<EtlTaskInstanceMapper, EtlTaskInstance> implements EtlTaskInstanceService {

    @Override
    protected Class<EtlTaskInstance> getClaas() {
        return EtlTaskInstance.class;
    }

    @Override
    public EtlTaskInstance createRunningInstance(EtlPipeline pipeline, TriggerTypeEnum triggerType, int retryCount) {
        EtlTaskInstance instance = new EtlTaskInstance();
        instance.setPipelineId(pipeline.getId());
        instance.setPipelineCode(pipeline.getPipelineCode());
        instance.setPipelineName(pipeline.getPipelineName());
        instance.setTriggerType(triggerType);
        instance.setTaskStatus(TaskStatusEnum.RUNNING);
        instance.setStartTime(new Date());
        instance.setReadCount(0L);
        instance.setWriteCount(0L);
        // 重试次数必须落库：否则每次重试都从 0 开始，失败判定永远成立会无限重试
        instance.setRetryCount(Math.max(retryCount, 0));
        instance.setLogDetail("[任务启动] 批次正在初始化...\n");

        this.save(instance);
        return instance;
    }
}
