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
        String script = pipeline.getTransformSql();
        if (StrUtil.isBlank(script)) {
            return 0;
        }

        // 1. 变量解析与替换
        String resolvedSql = resolveVariables(script, pipeline, stageTable, batchId);
        logBuffer.append("[SQL处理] 变量替换完成，准备执行脚本:\n").append(resolvedSql).append("\n");

        DataSource ds = datasourceService.getDataSource(targetDsId);
        long totalAffectedRows = 0;

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // 按分号切分多条 SQL 语句逐条执行
            List<String> sqlList = StrUtil.splitTrim(resolvedSql, ';');
            for (String sql : sqlList) {
                if (StrUtil.isBlank(sql)) {
                    continue;
                }
                log.info("执行 SQL 节点: {}", sql);
                boolean hasResultSet = stmt.execute(sql);
                if (!hasResultSet) {
                    int updateCount = stmt.getUpdateCount();
                    if (updateCount > 0) {
                        totalAffectedRows += updateCount;
                    }
                }
            }
        }

        logBuffer.append(String.format("[SQL处理] 脚本执行成功，累计影响行数: %d\n", totalAffectedRows));
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
