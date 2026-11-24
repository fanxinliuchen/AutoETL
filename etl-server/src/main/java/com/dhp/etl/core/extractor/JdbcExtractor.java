package com.dhp.etl.core.extractor;

import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.util.JdbcValueNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * JDBC 源库流式抽取（MySQL / PostgreSQL）。
 *
 * <p>两边的流式机制不同，不能共用一套参数：
 * <ul>
 *   <li>MySQL：TYPE_FORWARD_ONLY + CONCUR_READ_ONLY + fetchSize=Integer.MIN_VALUE，驱动内部流式；</li>
 *   <li>PostgreSQL：必须关闭自动提交且 fetchSize&gt;0，走服务端游标，
 *       否则驱动会把整个结果集读进内存。</li>
 * </ul>
 * 对下游统一走分批回调，临时表/入库链路完全不感知方言。
 */
@Slf4j
@Component
public class JdbcExtractor {

    private static final int DEFAULT_BATCH_SIZE = 2000;

    /** PG 游标每次抓取的行数 */
    private static final int PG_FETCH_SIZE = 2000;

    /**
     * @param connType      源连接类型（MYSQL / POSTGRESQL），决定流式参数
     * @param querySql      抽取 SQL（变量已替换）
     * @param batchConsumer 分批消费回调（每批行数据 + 日志缓冲）
     * @return 抽取总行数
     */
    public long extractInBatches(DataSource dataSource,
                                 ConnTypeEnum connType,
                                 String querySql,
                                 BiConsumer<List<Map<String, Object>>, StringBuilder> batchConsumer,
                                 StringBuilder logBuffer) throws Exception {
        boolean postgres = connType == ConnTypeEnum.POSTGRESQL;
        long totalCount = 0;
        try (Connection conn = dataSource.getConnection()) {
            if (postgres) {
                // 游标模式必须关自动提交；同时把事务设为只读，从源头杜绝源库误写
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SET TRANSACTION READ ONLY");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(querySql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(postgres ? PG_FETCH_SIZE : Integer.MIN_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<Map<String, Object>> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);

                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            // 方言对象（PG 的 PGobject/UUID/PgArray 等）在这里收敛成标准类型，
                            // 否则 MySQL 驱动会把它们做成 Java 序列化字节流写入
                            row.put(meta.getColumnLabel(i), JdbcValueNormalizer.normalize(rs.getObject(i)));
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
            if (postgres) {
                conn.commit();
            }
        }
        logBuffer.append(String.format("[%s抽取] 游标流式读取完毕，总行数: %d\n",
                postgres ? "PostgreSQL" : "MySQL", totalCount));
        return totalCount;
    }
}
