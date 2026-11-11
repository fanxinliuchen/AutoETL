package com.dhp.etl.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.alert.AlertService;
import com.dhp.etl.core.extractor.MySqlExtractor;
import com.dhp.etl.core.extractor.RequestVarResolver;
import com.dhp.etl.core.extractor.WebApiExtractor;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.loader.MySqlDwLoader;
import com.dhp.etl.core.loop.LoopConfig;
import com.dhp.etl.core.loop.LoopDatasetService;
import com.dhp.etl.core.transformer.AutoInsertSqlBuilder;
import com.dhp.etl.core.transformer.SqlTransformExecutor;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.enums.TaskStatusEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.enums.WriteModeEnum;
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
import java.util.Collections;
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
    private final LoopDatasetService loopDatasetService;
    private final RequestVarResolver requestVarResolver;
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
                            LoopDatasetService loopDatasetService,
                            RequestVarResolver requestVarResolver,
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
        this.loopDatasetService = loopDatasetService;
        this.requestVarResolver = requestVarResolver;
        this.mySqlDwLoader = mySqlDwLoader;
        this.sqlSafetyGuard = sqlSafetyGuard;
        this.alertService = alertService;
        this.datasourceService = datasourceService;
        this.instanceService = instanceService;
        this.pipelineService = pipelineService;
    }

    /**
     * 触发执行一个流程。
     *
     * <p>配置了「循环取数」时，先执行循环数据集，再对每一行参数跑一遍完整流程。
     * 串行执行以复用同一个 staging 表；某轮失败即中止后续轮次，避免参数有问题时批量写入半成品数据。
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
        // 增量同步依赖系统生成「软删除 + UPSERT」脚本，与手写脚本互斥，避免两套逻辑叠加
        if (pipeline.getWriteMode() == WriteModeEnum.SYNC && StrUtil.isNotBlank(pipeline.getTransformSql())) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "写入模式为「增量同步」时不能同时填写转换 SQL：删除判定与合并写入由系统生成。"
                            + "如需自定义清洗逻辑，请改用「主键更新插入(UPSERT)」并自行处理删除");
        }

        // 并发防重保护
        if (runningTasks.containsKey(pipelineId)) {
            log.warn("[ETL] 流程 {} 正在运行中，忽略本次触发", pipeline.getPipelineCode());
            return;
        }

        EtlTaskInstance instance = instanceService.createRunningInstance(pipeline, triggerType);
        StringBuilder logBuffer = new StringBuilder();
        boolean looped = false;
        int totalRounds = 1;
        int finishedRounds = 0;

        try {
            runningTasks.put(pipelineId, CompletableFuture.completedFuture(null));
            logBuffer.append(String.format("[%s] ===== ETL 流程启动: %s =====\n",
                    LocalDateTime.now(), pipeline.getPipelineName()));

            // 临时表名提前确定：未配置转换 SQL 时，入库脚本要按它生成
            String stageTable = "stg_" + pipeline.getPipelineCode().toLowerCase();
            boolean autoTransform = StrUtil.isBlank(pipeline.getTransformSql());
            Map<String, String> columnTypes = FieldMetaResolver.parseColumnTypes(pipeline.getFieldMeta());
            // 附加列同样要建进临时表，类型取配置（未配置按 VARCHAR(255)）
            for (RequestVarResolver.ExtraColumn extra : RequestVarResolver.parseExtraColumns(pipeline.getFilterMeta())) {
                columnTypes.putIfAbsent(extra.getName(), FieldMetaResolver.normalizeType(extra.getType()));
            }

            // 1. SQL 预检安全守卫（每轮不变，只做一次）
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

            // 2. 展开循环参数（未启用时只有一轮空参数）
            LoopConfig loop = LoopConfig.parse(pipeline.getFilterMeta());
            List<Map<String, String>> rounds;
            if (loop == null) {
                rounds = Collections.singletonList(Collections.emptyMap());
            } else {
                rounds = loopDatasetService.fetch(pipeline.getTargetDsId(), loop, logBuffer);
                totalRounds = rounds.size();
                looped = true;
                logBuffer.append(String.format("[循环] 循环取数已启用，共 %d 轮，串行执行\n", totalRounds));
            }

            // 3. 逐轮执行
            long totalRead = 0;
            long totalWrite = 0;
            for (int i = 0; i < rounds.size(); i++) {
                Map<String, String> vars = rounds.get(i);
                if (looped) {
                    logBuffer.append(String.format("[循环] ===== 第 %d/%d 轮: %s =====\n",
                            i + 1, totalRounds, vars));
                }
                long[] rw = runOnce(pipeline, instance.getId(), stageTable, autoTransform, columnTypes, logBuffer, vars);
                totalRead += rw[0];
                totalWrite += rw[1];
                finishedRounds = i + 1;

                if (looped && loop.getIntervalMs() > 0 && i < rounds.size() - 1) {
                    try {
                        Thread.sleep(loop.getIntervalMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "循环执行被中断");
                    }
                }
            }

            instance.setReadCount(totalRead);
            instance.setWriteCount(totalWrite);
            if (looped) {
                logBuffer.append(String.format("[循环] 全部 %d 轮执行完成，累计读取 %d 行、写入 %d 行\n",
                        totalRounds, totalRead, totalWrite));
            }

            // 4. 标记执行成功
            instance.setTaskStatus(TaskStatusEnum.SUCCESS);
            pipeline.setLastRunStatus(TaskStatusEnum.SUCCESS.getCode());
            logBuffer.append(String.format("[%s] ===== 流程执行成功结束 =====\n", LocalDateTime.now()));

        } catch (Exception ex) {
            log.error("[ETL] 流程执行失败: {}", pipeline.getPipelineCode(), ex);
            if (looped && finishedRounds < totalRounds) {
                int remaining = Math.max(totalRounds - finishedRounds - 1, 0);
                logBuffer.append(String.format("[循环] 第 %d 轮失败，后续 %d 轮已中止（共 %d 轮）\n",
                        finishedRounds + 1, remaining, totalRounds));
            }
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
     * 执行一轮完整流程：抽取 → 暂存 → 转换入库 → 清理临时表
     *
     * @param loopVars 循环取数当前轮的参数（列名 -> 值），为空表示非循环
     * @return {读取行数, 写入行数}
     */
    private long[] runOnce(EtlPipeline pipeline,
                           Long instanceId,
                           String stageTable,
                           boolean autoTransform,
                           Map<String, String> columnTypes,
                           StringBuilder logBuffer,
                           Map<String, String> loopVars) throws Exception {
        // 清空旧的临时表
        mySqlDwLoader.truncateStageTable(pipeline.getTargetDsId(), stageTable);

        // 附加列：每轮算一次（变量可能随循环参数变化），再补到每行
        Map<String, String> extraValues = requestVarResolver.resolveExtraColumns(pipeline, logBuffer, loopVars);
        if (!extraValues.isEmpty()) {
            logBuffer.append("[附加列] ").append(extraValues).append("\n");
        }

        long readCount;
        if (pipeline.getSourceType() == SourceTypeEnum.MYSQL) {
            if (pipeline.getSourceDsId() == null || StrUtil.isBlank(pipeline.getSourceQuerySql())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "MySQL 源缺少数据源ID或抽取SQL");
            }
            DataSource srcDs = datasourceService.getDataSource(pipeline.getSourceDsId());
            // 源 SQL 同样支持变量：循环时每轮取不同的时间区间
            String sourceSql = requestVarResolver.resolve(pipeline, pipeline.getSourceQuerySql(), logBuffer, loopVars);
            readCount = mySqlExtractor.extractInBatches(
                    srcDs,
                    sourceSql,
                    (batch, buf) -> {
                        injectExtraColumns(batch, extraValues);
                        mySqlDwLoader.batchInsertToStage(pipeline.getTargetDsId(), stageTable, batch, columnTypes, buf);
                    },
                    logBuffer
            );
        } else {
            List<Map<String, Object>> apiRecords = webApiExtractor.extract(pipeline, logBuffer, loopVars);
            readCount = apiRecords.size();
            if (CollUtil.isNotEmpty(apiRecords)) {
                injectExtraColumns(apiRecords, extraValues);
                mySqlDwLoader.batchInsertToStage(pipeline.getTargetDsId(), stageTable, apiRecords, columnTypes, logBuffer);
            }
        }
        logBuffer.append(String.format("[暂存] 数据已成功载入临时表 %s，行数: %d\n", stageTable, readCount));

        // 转换与入库
        long affectedRows = 0;
        if (autoTransform) {
            if (readCount == 0) {
                logBuffer.append("[处理] 未配置转换 SQL 且本次抽取 0 行，跳过入库\n");
            } else {
                String autoSql = autoInsertSqlBuilder.build(pipeline, stageTable, logBuffer);
                logBuffer.append("[处理] 自动生成的入库脚本:\n").append(autoSql).append("\n");
                sqlSafetyGuard.validate(autoSql);
                affectedRows = sqlTransformExecutor.executeScript(
                        pipeline.getTargetDsId(),
                        pipeline,
                        stageTable,
                        instanceId,
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
                    instanceId,
                    logBuffer
            );
        }
        logBuffer.append(String.format("[入库] 入库执行完成，目标受影响行数: %d\n", affectedRows));

        // 清空临时表
        mySqlDwLoader.truncateStageTable(pipeline.getTargetDsId(), stageTable);
        return new long[]{readCount, affectedRows};
    }

    /**
     * 把附加列补进每一行；抽取结果里已有同名列时不覆盖（以源数据为准）
     */
    private void injectExtraColumns(List<Map<String, Object>> rows, Map<String, String> extraValues) {
        if (CollUtil.isEmpty(rows) || extraValues == null || extraValues.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            extraValues.forEach(row::putIfAbsent);
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
