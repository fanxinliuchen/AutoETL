package com.dhp.etl.core.loop;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.extractor.RequestVarResolver;
import com.dhp.etl.service.EtlDatasourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环数据集执行器：把一段 SQL 的多行结果转成「多轮参数集」。
 *
 * <p>每行是一轮，列名即变量名。执行时按轮次注入变量，覆盖变量定义里的同名项。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoopDatasetService {

    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final EtlDatasourceService datasourceService;

    /**
     * 执行循环 SQL，返回每轮的参数集（列名 -> 值）
     */
    public List<Map<String, String>> fetch(Long datasourceId, LoopConfig config, StringBuilder logBuffer) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "循环取数需要先配置目标数据源：循环 SQL 在目标库上执行");
        }
        // 与变量 SQL 用同一套安全约束：单语句、禁文件读写与拖库函数
        RequestVarResolver.validateSql("循环数据集", config.getSql());

        DataSource dataSource = datasourceService.getDataSource(datasourceId);
        List<Map<String, String>> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            try (ResultSet rs = statement.executeQuery(config.getSql())) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                while (rs.next()) {
                    if (rows.size() >= config.getMaxIterations()) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                "循环数据集超过上限 " + config.getMaxIterations()
                                        + " 行，请收窄 SQL 范围或调高「最多循环次数」");
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.put(columns.get(i - 1), value == null ? "" : String.valueOf(value));
                    }
                    rows.add(row);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("循环数据集执行失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "循环数据集执行失败: " + e.getMessage());
        }

        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "循环数据集没有返回任何行，流程不会执行。请检查 SQL 的过滤条件或日期范围");
        }
        if (logBuffer != null) {
            logBuffer.append(String.format("[循环] 循环数据集共 %d 行，列: %s\n",
                    rows.size(), String.join(", ", rows.get(0).keySet())));
        }
        return rows;
    }

    /**
     * 试算：返回列名与预览行，供界面确认循环规模
     */
    public Map<String, Object> preview(Long datasourceId, String sql, int limit) {
        if (StrUtil.isBlank(sql)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "循环 SQL 不能为空");
        }
        RequestVarResolver.validateSql("循环数据集", sql);
        if (datasourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先选择目标数据源");
        }
        int previewLimit = Math.max(1, Math.min(limit, 50));

        DataSource dataSource = datasourceService.getDataSource(datasourceId);
        List<String> columns = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        int total = 0;

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                while (rs.next()) {
                    total++;
                    if (rows.size() < previewLimit) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            row.put(columns.get(i - 1), value == null ? "" : String.valueOf(value));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "循环 SQL 执行失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rowCount", total);
        result.put("rows", rows);
        return result;
    }
}
