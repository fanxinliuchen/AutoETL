package com.dhp.etl.core.loader;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.util.FieldMetaResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 数仓加载器 - 临时表暂存 (Staging) 与批量写入 (Loader)
 *
 * <p>临时表列类型来自流程的 field_meta（经白名单归一化），未配置类型的列仍为 TEXT。
 * 由于 DDL 使用 CREATE TABLE IF NOT EXISTS，列类型或列集合变化时不会自动生效，
 * 因此这里会比对 information_schema 并在结构不一致时重建临时表（表在每次抽取前已被 TRUNCATE）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlDwLoader {

    private final EtlDatasourceService datasourceService;

    /**
     * 批量灌入 Staging 临时表中
     *
     * @param columnTypes 列名 -> 建表类型（可为 null，全部按 TEXT）
     * @param logBuffer   执行日志缓冲（首次建表 / 结构重建时写入提示）
     */
    public void batchInsertToStage(Long targetDsId,
                                   String stageTable,
                                   List<Map<String, Object>> rows,
                                   Map<String, String> columnTypes,
                                   StringBuilder logBuffer) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        batchInsertToStage(targetDsId, stageTable, rows, columnTypes,
                new ArrayList<>(rows.get(0).keySet()), logBuffer);
    }

    /**
     * 指定列集合的分批写入：列在整轮抽取开始时确定，后续每页都用同一套列。
     *
     * <p>流式抽取（一页一写）必须走这个重载：不能按「每页首行的 key 集合」推列，
     * 否则某页少一个键就会被判成结构变更、触发临时表重建，前面已写入的页全丢。
     */
    public void batchInsertToStage(Long targetDsId,
                                   String stageTable,
                                   List<Map<String, Object>> rows,
                                   Map<String, String> columnTypes,
                                   List<String> columns,
                                   StringBuilder logBuffer) {
        if (CollUtil.isEmpty(rows) || CollUtil.isEmpty(columns)) {
            return;
        }

        DataSource ds = datasourceService.getDataSource(targetDsId);
        if (logBuffer != null) {
            logBuffer.append("[暂存] 本次入库列数: ").append(columns.size())
                    .append("，示例: ").append(columns.subList(0, Math.min(5, columns.size()))).append("\n");
        }

        try (Connection conn = ds.getConnection()) {
            // 确保临时中转表存在（含类型校验与结构重建），拿到归一化后的列类型
            Map<String, String> expected = ensureStageTableExists(conn, stageTable, columns, columnTypes, logBuffer);

            StringJoiner colNames = new StringJoiner("`, `", "`", "`");
            StringJoiner placeholders = new StringJoiner(", ");
            for (String col : columns) {
                colNames.add(safeIdent(col));
                placeholders.add("?");
            }

            String insertSql = String.format("INSERT INTO `%s` (%s) VALUES (%s)",
                    safeIdent(stageTable), colNames, placeholders);

            Set<String> blankedColumns = new LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        String column = columns.get(i);
                        Object value = row.get(column);
                        // 金蝶对空值返回的是 " "（一个空格）而不是 null：落 INT / DECIMAL / DATE
                        // 这类列会被严格模式拒掉（Incorrect integer value: ' '），这里按 NULL 写入。
                        // 文本列原样保留，避免把空串改成 NULL 改变下游判空口径。
                        if (isBlankForColumn(value, expected.get(column))) {
                            blankedColumns.add(column);
                            value = null;
                        }
                        ps.setObject(i + 1, value);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (!blankedColumns.isEmpty() && logBuffer != null) {
                logBuffer.append("[暂存] 以下非文本列收到空值，已按 NULL 写入: ")
                        .append(blankedColumns).append("\n");
            }
        } catch (Exception e) {
            log.error("批量写入临时表 {} 失败: {}", stageTable, e.getMessage());
            throw new RuntimeException("批量写入临时表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 非文本列收到空白串时按 NULL 处理；文本列不动
     */
    static boolean isBlankForColumn(Object value, String columnType) {
        if (!(value instanceof CharSequence text) || StrUtil.isNotBlank(text)) {
            return false;
        }
        return !isTextColumn(columnType);
    }

    static boolean isTextColumn(String columnType) {
        String upper = StrUtil.nullToEmpty(columnType).toUpperCase(Locale.ROOT);
        if (upper.isEmpty()) {
            return true;
        }
        return upper.startsWith("VARCHAR") || upper.startsWith("CHAR") || upper.contains("TEXT")
                || upper.startsWith("ENUM") || upper.startsWith("SET") || upper.startsWith("JSON");
    }

    /**
     * 清理临时表数据
     */
    public void truncateStageTable(Long targetDsId, String stageTable) {
        DataSource ds = datasourceService.getDataSource(targetDsId);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE `" + safeIdent(stageTable) + "`");
        } catch (Exception e) {
            log.warn("清空临时表 {} 提示: {}", stageTable, e.getMessage());
        }
    }

    /**
     * 给临时表建「比对键」索引，供 SYNC 软删除的 NOT EXISTS 探针使用。
     *
     * <p>临时表默认只有自增 `_stage_id` 主键，业务键没有索引；28 万目标行 × 29 万临时行
     * 的 NOT EXISTS 会退化成全表扫描。索引在抽取完成、写回之前建：建表时建会让每次
     * 分页插入都维护索引，反而拖慢抽取。
     *
     * <p>建索引失败只告警不阻断：最坏情况是写回慢，而不是整批失败。
     */
    public void ensureStageIndex(Long targetDsId, String stageTable, List<String> columns, StringBuilder logBuffer) {
        if (targetDsId == null || StrUtil.isBlank(stageTable) || CollUtil.isEmpty(columns)) {
            return;
        }
        String indexName = "idx_etl_merge_key";
        try (Connection conn = datasourceService.getDataSource(targetDsId).getConnection()) {
            if (indexExists(conn, stageTable, indexName)) {
                return;
            }
            StringJoiner joined = new StringJoiner("`, `", "`", "`");
            columns.forEach(joined::add);
            long start = System.currentTimeMillis();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE `" + safeIdent(stageTable) + "` ADD INDEX `"
                        + indexName + "` (" + joined + ")");
            }
            if (logBuffer != null) {
                logBuffer.append(String.format("[暂存] 已为临时表建立比对索引 %s(%s)，耗时 %d ms\n",
                        indexName, String.join(", ", columns), System.currentTimeMillis() - start));
            }
        } catch (Exception e) {
            log.warn("为临时表 {} 建立比对索引失败（写回仍可执行）: {}", stageTable, e.getMessage());
            if (logBuffer != null) {
                logBuffer.append("[暂存] 提示：比对索引创建失败，写回可能较慢：")
                        .append(StrUtil.brief(StrUtil.nullToEmpty(e.getMessage()), 120)).append("\n");
            }
        }
    }

    private boolean indexExists(Connection conn, String tableName, String indexName) {
        String sql = "SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> ensureStageTableExists(Connection conn,
                                                       String tableName,
                                                       List<String> columns,
                                                       Map<String, String> columnTypes,
                                                       StringBuilder logBuffer) throws Exception {
        // 1. 归一化每列的目标类型（白名单，非法值降级 TEXT）
        Map<String, String> expected = new LinkedHashMap<>();
        for (String col : columns) {
            String rawType = columnTypes == null ? null : columnTypes.get(col);
            expected.put(col, FieldMetaResolver.normalizeType(rawType));
        }

        // 2. 已存在但结构不一致时重建（否则 CREATE TABLE IF NOT EXISTS 会静默沿用旧结构）
        boolean exists = tableExists(conn, tableName);
        boolean rebuilt = false;
        if (exists && !schemaMatches(conn, tableName, expected)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE `" + safeIdent(tableName) + "`");
            }
            rebuilt = true;
            log.info("临时表 {} 列结构变更，已重建", tableName);
        }

        // 3. 建表
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS `")
                .append(safeIdent(tableName)).append("` (");
        ddl.append("`_stage_id` BIGINT AUTO_INCREMENT PRIMARY KEY, ");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            ddl.append("`").append(safeIdent(entry.getKey())).append("` ")
                    .append(entry.getValue()).append(" NULL, ");
        }
        ddl.setLength(ddl.length() - 2);
        // 显式 DYNAMIC：字段多（几十列 TEXT/VARCHAR）时，COMPACT 行格式会把每列前缀存在行内，
        // 直接触发 "Row size too large (> 8126)"，DYNAMIC 则把大字段整体挪到溢出页
        ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl.toString());
        } catch (java.sql.SQLException e) {
            // 建表失败时把版本/字符集/行格式带出来，行数多了容易出现行大小或兼容性问题
            String version = "unknown";
            String charset = "unknown";
            try {
                version = conn.getMetaData().getDatabaseProductVersion();
                try (Statement s = conn.createStatement();
                     ResultSet rs = s.executeQuery(
                             "SELECT @@character_set_database, @@collation_database, @@innodb_default_row_format")) {
                    if (rs.next()) {
                        charset = rs.getString(1) + "/" + rs.getString(2) + ", default_row_format=" + rs.getString(3);
                    }
                }
            } catch (Exception ignored) {
            }
            throw new java.sql.SQLException(
                    "创建临时表失败: " + e.getMessage() + "；列数=" + expected.size()
                            + "，服务端版本=" + version + "，库字符集=" + charset, e);
        }

        if (logBuffer != null && (!exists || rebuilt)) {
            logBuffer.append(rebuilt ? "[暂存] 临时表列结构变更，已重建: " : "[暂存] 已创建临时表: ")
                    .append(tableName).append("\n")
                    .append("[暂存] 列类型: ").append(expected).append("\n");
        }
        return expected;
    }

    private boolean tableExists(Connection conn, String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 比对现有表与期望结构（列集合 + 基础类型），_stage_id 不参与比较
     */
    private boolean schemaMatches(Connection conn, String tableName, Map<String, String> expected) throws Exception {
        Map<String, String> actual = new HashMap<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if ("_stage_id".equalsIgnoreCase(name)) {
                        continue;
                    }
                    String dataType = rs.getString(2);
                    actual.put(name, dataType == null ? "" : dataType.toLowerCase());
                }
            }
        }
        if (actual.size() != expected.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String current = actual.get(entry.getKey());
            if (current == null || !current.equals(FieldMetaResolver.baseType(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 标识符转义：临时表名与列名最终会被拼进 DDL/DML，反引号必须成对转义
     */
    private String safeIdent(String ident) {
        return ident == null ? "" : ident.replace("`", "``");
    }
}
