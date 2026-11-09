package com.dhp.etl.core.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * MySQL 数据流式抽取组件 (方案修正 P1-2)
 */
@Slf4j
@Component
public class MySqlExtractor {

    private static final int DEFAULT_BATCH_SIZE = 2000;

    /**
     * 流式游标分批读取，避免大表 OOM
     *
     * @param dataSource    源数据库连接池
     * @param querySql      抽取 SQL
     * @param batchConsumer 分批消费回调 (接收每批行数据和日志缓冲)
     * @param logBuffer     执行日志缓存
     * @return 抽取总行数
     */
    public long extractInBatches(DataSource dataSource,
                                 String querySql,
                                 BiConsumer<List<Map<String, Object>>, StringBuilder> batchConsumer,
                                 StringBuilder logBuffer) throws Exception {
        long totalCount = 0;
        try (Connection conn = dataSource.getConnection()) {
            // MySQL 流式结果集特性: TYPE_FORWARD_ONLY + CONCUR_READ_ONLY + fetchSize=Integer.MIN_VALUE
            try (PreparedStatement ps = conn.prepareStatement(querySql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<Map<String, Object>> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                        }
                        batch.add(row);
                        totalCount++;

                        if (batch.size() >= DEFAULT_BATCH_SIZE) {
                            batchConsumer.accept(batch, logBuffer);
                            batch = new ArrayList<>(DEFAULT_BATCH_SIZE);
                        }
                    }
                    if (!batch.isEmpty()) {
                        batchConsumer.accept(batch, logBuffer);
                    }
                }
            }
        }
        logBuffer.append(String.format("[MySQL抽取] 游标流式读取完毕，总行数: %d\n", totalCount));
        return totalCount;
    }
}
