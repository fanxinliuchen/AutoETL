package com.dhp.etl.core.schedule;

import cn.hutool.core.util.StrUtil;
import com.dhp.etl.core.PipelineExecutor;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.service.EtlPipelineService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态 Cron 调度器中心 (含启动自动恢复)
 */
@Slf4j
@Component
public class DynamicCronScheduler {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final PipelineExecutor pipelineExecutor;
    private final EtlPipelineService pipelineService;

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public DynamicCronScheduler(ThreadPoolTaskScheduler taskScheduler,
                                PipelineExecutor pipelineExecutor,
                                @Lazy EtlPipelineService pipelineService) {
        this.taskScheduler = taskScheduler;
        this.pipelineExecutor = pipelineExecutor;
        this.pipelineService = pipelineService;
    }

    /**
     * 容器完全就绪后，自动从数据库恢复所有处于启用状态的 Cron 调度任务
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverScheduledTasks() {
        try {
            List<EtlPipeline> enabledList = pipelineService.lambdaQuery()
                    .eq(EtlPipeline::getScheduleStatus, 1)
                    .isNotNull(EtlPipeline::getCronExpr)
                    .list();

            log.info("[ETL调度恢复] 启动扫描，待恢复调度流程数: {}", enabledList.size());
            for (EtlPipeline pipeline : enabledList) {
                try {
                    registerTask(pipeline.getId(), pipeline.getCronExpr());
                    log.info("[ETL调度恢复] 成功恢复定时调度: 流程={}, Cron={}", pipeline.getPipelineName(), pipeline.getCronExpr());
                } catch (Exception e) {
                    log.error("[ETL调度恢复] 恢复失败: 流程={}, 原因: {}", pipeline.getPipelineCode(), e.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("[ETL调度恢复] 数据库未就绪或表尚未初始化，跳过恢复: {}", ex.getMessage());
        }
    }

    /**
     * 注册或更新调度
     */
    public void registerTask(Long pipelineId, String cronExpr) {
        stopTask(pipelineId);

        if (StrUtil.isNotBlank(cronExpr)) {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> pipelineExecutor.execute(pipelineId, TriggerTypeEnum.CRON),
                    new CronTrigger(cronExpr)
            );
            scheduledTasks.put(pipelineId, future);
        }
    }

    /**
     * 停止调度
     */
    public void stopTask(Long pipelineId) {
        ScheduledFuture<?> future = scheduledTasks.remove(pipelineId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public int getActiveCount() {
        return scheduledTasks.size();
    }
}
