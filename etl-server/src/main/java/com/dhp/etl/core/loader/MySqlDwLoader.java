package com.dhp.etl.core.loader;

import cn.hutool.core.collection.CollUtil;
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
import java.util.List;
import java.util.Map;
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

        DataSource ds = datasourceService.getDataSource(targetDsId);
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        if (logBuffer != null) {
            logBuffer.append("[暂存] 本次入库列数: ").append(columns.size())
                    .append("，示例: ").append(columns.subList(0, Math.min(5, columns.size()))).append("\n");
        }

        try (Connection conn = ds.getConnection()) {
            // 确保临时中转表存在（含类型校验与结构重建）
            ensureStageTableExists(conn, stageTable, columns, columnTypes, logBuffer);

            StringJoiner colNames = new StringJoiner("`, `", "`", "`");
            StringJoiner placeholders = new StringJoiner(", ");
            for (String col : columns) {
                colNames.add(safeIdent(col));
                placeholders.add("?");
            }

            String insertSql = String.format("INSERT INTO `%s` (%s) VALUES (%s)",
                    safeIdent(stageTable), colNames, placeholders);

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (Map<String, Object> row : rows) {
                    for (int i = 0; i < columns.size(); i++) {
                        ps.setObject(i + 1, row.get(columns.get(i)));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception e) {
            log.error("批量写入临时表 {} 失败: {}", stageTable, e.getMessage());
            throw new RuntimeException("批量写入临时表失败: " + e.getMessage(), e);
        }
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

    private void ensureStageTableExists(Connection conn,
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
