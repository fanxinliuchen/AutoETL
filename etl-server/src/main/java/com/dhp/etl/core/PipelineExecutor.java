package com.dhp.etl.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.alert.AlertService;
import com.dhp.etl.core.extractor.MySqlExtractor;
import com.dhp.etl.core.extractor.WebApiExtractor;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.loader.MySqlDwLoader;
import com.dhp.etl.core.transformer.AutoInsertSqlBuilder;
import com.dhp.etl.core.transformer.SqlTransformExecutor;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.enums.TaskStatusEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.service.EtlPipelineService;
import com.dhp.etl.service.EtlTaskInstanceService;
import com.dhp.etl.util.FieldMetaResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ETL 核心调度流水线执行器 (全生命周期编排)
 */
@Slf4j
@Component
public class PipelineExecutor {

    private final MySqlExtractor mySqlExtractor;
    private final WebApiExtractor webApiExtractor;
    private final SqlTransformExecutor sqlTransformExecutor;
    private final AutoInsertSqlBuilder autoInsertSqlBuilder;
    private final MySqlDwLoader mySqlDwLoader;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final AlertService alertService;
    private final EtlDatasourceService datasourceService;
    private final EtlTaskInstanceService instanceService;
    private final EtlPipelineService pipelineService;

    private final ConcurrentHashMap<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public PipelineExecutor(MySqlExtractor mySqlExtractor,
                            WebApiExtractor webApiExtractor,
                            SqlTransformExecutor sqlTransformExecutor,
                            AutoInsertSqlBuilder autoInsertSqlBuilder,
                            MySqlDwLoader mySqlDwLoader,
                            SqlSafetyGuard sqlSafetyGuard,
                            AlertService alertService,
                            EtlDatasourceService datasourceService,
                            EtlTaskInstanceService instanceService,
                            @Lazy EtlPipelineService pipelineService) {
        this.mySqlExtractor = mySqlExtractor;
        this.webApiExtractor = webApiExtractor;
        this.sqlTransformExecutor = sqlTransformExecutor;
        this.autoInsertSqlBuilder = autoInsertSqlBuilder;
        this.mySqlDwLoader = mySqlDwLoader;
        this.sqlSafetyGuard = sqlSafetyGuard;
        this.alertService = alertService;
        this.datasourceService = datasourceService;
        this.instanceService = instanceService;
        this.pipelineService = pipelineService;
    }

    /**
     * 触发执行一个流程
     */
    public void execute(Long pipelineId, TriggerTypeEnum triggerType) {
        EtlPipeline pipeline = pipelineService.getById(pipelineId);
        if (pipeline == null) {
            log.warn("流程未找到: id={}", pipelineId);
            return;
        }

        // 转换 SQL 允许为空：为空时按目标表结构自动生成直通入库脚本
        if (pipeline.getTargetDsId() == null || StrUtil.isBlank(pipeline.getTargetTable())) {
            log.warn("[ETL] 流程 {} 属于草稿/未完整配置状态，无法执行", pipeline.getPipelineCode());
            throw new BizException(ErrorCode.PARAM_ERROR, "流程配置尚未完善（缺少目标数仓连接或目标表），请先编辑完善后再运行");
        }

        // 并发防重保护
        if (runningTasks.containsKey(pipelineId)) {
            log.warn("[ETL] 流程 {} 正在运行中，忽略本次触发", pipeline.getPipelineCode());
            return;
        }

        EtlTaskInstance instance = instanceService.createRunningInstance(pipeline, triggerType);
        StringBuilder logBuffer = new StringBuilder();

        try {
            runningTasks.put(pipelineId, CompletableFuture.completedFuture(null));
            logBuffer.append(String.format("[%s] ===== ETL 流程启动: %s =====\n",
                    LocalDateTime.now(), pipeline.getPipelineName()));

            // 临时表名提前确定：未配置转换 SQL 时，入库脚本要按它生成
            String stageTable = "stg_" + pipeline.getPipelineCode().toLowerCase();
            // 转换 SQL 为空时走「自动直通入库」，脚本在抽取完成后按目标表结构生成
            boolean autoTransform = StrUtil.isBlank(pipeline.getTransformSql());

            // 1. SQL 预检安全守卫
            logBuffer.append("[校验] 执行 SQL AST 安全白名单校验...\n");
            if (!autoTransform) {
                sqlSafetyGuard.validate(pipeline.getTransformSql());
            }
            if (pipeline.getSourceType() == SourceTypeEnum.MYSQL && StrUtil.isNotBlank(pipeline.getSourceQuerySql())) {
                sqlSafetyGuard.validate(pipeline.getSourceQuerySql());
            }
            logBuffer.append("[校验] SQL 语法与白名单校验通过\n");
            if (autoTransform) {
                logBuffer.append("[校验] 未配置转换 SQL，将在抽取完成后按目标表结构自动生成入库脚本\n");
            }

            // 2. 数据抽取与暂存 (Staging)
            // 临时表列类型来自字段清单（白名单归一化，未配置的列仍为 TEXT）
            Map<String, String> columnTypes = FieldMetaResolver.parseColumnTypes(pipeline.getFieldMeta());
            AtomicLong readCount = new AtomicLong(0);

            // 清空旧的临时表
            mySqlDwLoader.truncateStageTable(pipeline.getTargetDsId(), stageTable);

            if (pipeline.getSourceType() == SourceTypeEnum.MYSQL) {
                if (pipeline.getSourceDsId() == null || StrUtil.isBlank(pipeline.getSourceQuerySql())) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "MySQL 源缺少数据源ID或抽取SQL");
                }
                DataSource srcDs = datasourceService.getDataSource(pipeline.getSourceDsId());
                long total = mySqlExtractor.extractInBatches(
                        srcDs,
                        pipeline.getSourceQuerySql(),
                        (batch, buf) -> mySqlDwLoader.batchInsertToStage(pipeline.getTargetDsId(), stageTable, batch, columnTypes, buf),
                        logBuffer
                );
                readCount.set(total);
            } else {
                List<Map<String, Object>> apiRecords = webApiExtractor.extract(pipeline, logBuffer);
                readCount.set(apiRecords.size());
                if (CollUtil.isNotEmpty(apiRecords)) {
                    mySqlDwLoader.batchInsertToStage(pipeline.getTargetDsId(), stageTable, apiRecords, columnTypes, logBuffer);
                }
            }
            instance.setReadCount(readCount.get());
            logBuffer.append(String.format("[暂存] 数据已成功载入临时表 %s，行数: %d\n", stageTable, readCount.get()));

            // 3. 执行 SQL 转换与入库脚本 (Transform)
            long affectedRows = 0;
            if (autoTransform) {
                if (readCount.get() == 0) {
                    logBuffer.append("[处理] 未配置转换 SQL 且本次抽取 0 行，跳过入库\n");
                } else {
                    String autoSql = autoInsertSqlBuilder.build(pipeline, stageTable, logBuffer);
                    logBuffer.append("[处理] 自动生成的入库脚本:\n").append(autoSql).append("\n");
                    sqlSafetyGuard.validate(autoSql);
                    affectedRows = sqlTransformExecutor.executeScript(
                            pipeline.getTargetDsId(),
                            pipeline,
                            stageTable,
                            instance.getId(),
                            autoSql,
                            logBuffer
                    );
                }
            } else {
                logBuffer.append("[处理] 正在执行 SQL 处理与入库脚本...\n");
                affectedRows = sqlTransformExecutor.executeScript(
                        pipeline.getTargetDsId(),
                        pipeline,
                        stageTable,
                        instance.getId(),
                        logBuffer
                );
            }
            instance.setWriteCount(affectedRows);
            logBuffer.append(String.format("[入库] 入库执行完成，目标受影响行数: %d\n", affectedRows));

            // 4. 清空临时表 (可选清理)
            mySqlDwLoader.truncateStageTable(pipeline.getTargetDsId(), stageTable);

            // 5. 标记执行成功
            instance.setTaskStatus(TaskStatusEnum.SUCCESS);
            pipeline.setLastRunStatus(TaskStatusEnum.SUCCESS.getCode());
            logBuffer.append(String.format("[%s] ===== 流程执行成功结束 =====\n", LocalDateTime.now()));

        } catch (Exception ex) {
            log.error("[ETL] 流程执行失败: {}", pipeline.getPipelineCode(), ex);
            instance.setTaskStatus(TaskStatusEnum.FAILED);
            instance.setErrorMessage(StrUtil.sub(ex.getMessage(), 0, 2000));
            pipeline.setLastRunStatus(TaskStatusEnum.FAILED.getCode());
            logBuffer.append(String.format("[%s] ===== 流程发生异常 =====\n%s\n",
                    LocalDateTime.now(), ExceptionUtil.stacktraceToString(ex, 5000)));

            // 触发告警通知
            alertService.sendAlert(pipeline, instance, ex);

            // 失败重试逻辑
            if (instance.getRetryCount() < (pipeline.getMaxRetryCount() != null ? pipeline.getMaxRetryCount() : 0)) {
                int nextRetry = instance.getRetryCount() + 1;
                instance.setRetryCount(nextRetry);
                logBuffer.append(String.format("[重试] 准备执行第 %d 次自动重试...\n", nextRetry));
                CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)
                        .execute(() -> this.execute(pipelineId, triggerType));
            }
        } finally {
            runningTasks.remove(pipelineId);
            Date end = new Date();
            instance.setEndTime(end);
            if (instance.getStartTime() != null) {
                instance.setDurationMs(end.getTime() - instance.getStartTime().getTime());
            }
            instance.setLogDetail(logBuffer.toString());
            instanceService.updateById(instance);

            pipeline.setLastRunTime(end);
            pipelineService.updateById(pipeline);
        }
    }

    /**
     * 手动取消运行中的任务
     */
    public boolean cancelRunning(Long pipelineId) {
        Future<?> future = runningTasks.get(pipelineId);
        if (future != null) {
            boolean canceled = future.cancel(true);
            runningTasks.remove(pipelineId);
            return canceled;
        }
        return false;
    }
}
