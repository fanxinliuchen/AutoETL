package com.dhp.etl.core.transformer;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.service.EtlDatasourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

/**
 * SQL 处理与转换脚本执行引擎
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlTransformExecutor {

    private final EtlDatasourceService datasourceService;

    /**
     * 执行用户配置的 SQL 转换处理脚本
     *
     * @param targetDsId 目标数仓数据源ID
     * @param pipeline   当前流程
     * @param stageTable 临时表名
     * @param batchId    当前批次号
     * @param logBuffer  日志输出
     * @return 受影响的总行数
     */
    public long executeScript(Long targetDsId,
                              EtlPipeline pipeline,
                              String stageTable,
                              Long batchId,
                              StringBuilder logBuffer) throws Exception {
        return executeScript(targetDsId, pipeline, stageTable, batchId, pipeline.getTransformSql(), logBuffer);
    }

    /**
     * 执行指定脚本（用于「未配置转换 SQL 时自动生成」的场景，不会把生成的脚本写回流程配置）
     */
    public long executeScript(Long targetDsId,
                              EtlPipeline pipeline,
                              String stageTable,
                              Long batchId,
                              String script,
                              StringBuilder logBuffer) throws Exception {
        return executeScript(targetDsId, pipeline, stageTable, batchId, script, logBuffer, null);
    }

    /**
     * 带阶段名的脚本执行：逐条语句输出「开始 / 完成（影响行数 + 耗时）」。
     *
     * <p>仅用于自动生成的 SYNC 脚本（软删除 → 合并两段）：这两段在大表上各自可能跑几分钟，
     * 不打点的话日志里只有一个总耗时，出问题无法判断卡在哪一段。
     *
     * @param phaseLabels 与语句顺序对应的阶段名；为空表示不输出阶段日志
     */
    public long executeScript(Long targetDsId,
                              EtlPipeline pipeline,
                              String stageTable,
                              Long batchId,
                              String script,
                              StringBuilder logBuffer,
                              List<String> phaseLabels) throws Exception {
        if (StrUtil.isBlank(script)) {
            return 0;
        }

        // 1. 变量解析与替换
        String resolvedSql = resolveVariables(script, pipeline, stageTable, batchId);
        logBuffer.append("[SQL处理] 变量替换完成，准备执行脚本:\n").append(resolvedSql).append("\n");

        DataSource ds = datasourceService.getDataSource(targetDsId);
        long totalAffectedRows = 0;
        long scriptStart = System.currentTimeMillis();

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // 按分号切分多条 SQL 语句逐条执行
            List<String> sqlList = StrUtil.splitTrim(resolvedSql, ';');
            int phaseIndex = 0;
            for (String sql : sqlList) {
                if (StrUtil.isBlank(sql)) {
                    continue;
                }
                String label = phaseLabels != null && phaseIndex < phaseLabels.size()
                        ? phaseLabels.get(phaseIndex) : null;
                phaseIndex++;

                log.info("执行 SQL 节点: {}", sql);
                long start = System.currentTimeMillis();
                if (label != null) {
                    logBuffer.append(String.format("[SQL处理] 开始执行%s...\n", label));
                }
                boolean hasResultSet = stmt.execute(sql);
                long cost = System.currentTimeMillis() - start;
                int updateCount = 0;
                if (!hasResultSet) {
                    updateCount = Math.max(stmt.getUpdateCount(), 0);
                    totalAffectedRows += updateCount;
                }
                if (label != null) {
                    logBuffer.append(String.format("[SQL处理] %s完成，影响 %d 行，耗时 %d ms\n",
                            label, updateCount, cost));
                }
            }
        }

        logBuffer.append(String.format("[SQL处理] 脚本执行成功，累计影响行数: %d，总耗时 %d ms\n",
                totalAffectedRows, System.currentTimeMillis() - scriptStart));
        return totalAffectedRows;
    }

    private String resolveVariables(String rawSql, EtlPipeline pipeline, String stageTable, Long batchId) {
        Date now = new Date();
        String today = DateUtil.formatDate(now);
        String yesterday = DateUtil.formatDate(DateUtil.yesterday());
        String nowStr = DateUtil.formatDateTime(now);

        return rawSql
                .replace("${today}", today)
                .replace("${yesterday}", yesterday)
                .replace("${now}", nowStr)
                .replace("${batch_id}", String.valueOf(batchId))
                .replace("${pipeline_code}", pipeline.getPipelineCode())
                .replace("${stage_table}", stageTable)
                .replace("${target_table}", StrUtil.blankToDefault(pipeline.getTargetTable(), ""));
    }
}
