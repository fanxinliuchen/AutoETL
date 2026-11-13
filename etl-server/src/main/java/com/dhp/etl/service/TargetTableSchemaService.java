package com.dhp.etl.service;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.util.FieldMetaResolver;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 目标表结构浏览与变更。
 *
 * <p>用于流程配置页的「字段映射」：列出目标库中的表、读取表结构，
 * 并按映射结果新增/删除列、设置主键。
 *
 * <p>所有表名、列名都走白名单校验，列类型走 {@link FieldMetaResolver#normalizeType} 白名单，
 * 避免把用户输入直接拼进 DDL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetTableSchemaService {

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private final EtlDatasourceService datasourceService;

    /**
     * 列出目标库中的全部表
     */
    public List<String> listTables(Long datasourceId) {
        DataSource ds = datasourceService.getDataSource(datasourceId);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
                             + "ORDER BY TABLE_NAME")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return tables;
        } catch (Exception e) {
            throw new BizException(ErrorCode.ETL_DATASOURCE_CONNECT_FAIL,
                    "读取目标库表清单失败: " + e.getMessage());
        }
    }

    /**
     * 读取表结构；表不存在返回空列表（不报错，便于前端展示"全空"状态）
     */
    public List<TableColumn> describeTable(Long datasourceId, String table) {
        String tableName = validateTable(table);
        DataSource ds = datasourceService.getDataSource(datasourceId);
        try (Connection conn = ds.getConnection()) {
            if (!tableExists(conn, tableName)) {
                return Collections.emptyList();
            }
            Set<String> primaryKeys = new LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' "
                            + "ORDER BY ORDINAL_POSITION")) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        primaryKeys.add(rs.getString(1));
                    }
                }
            }

            List<TableColumn> columns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, EXTRA "
                            + "FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        columns.add(new TableColumn(
                                name,
                                rs.getString(2),
                                "YES".equalsIgnoreCase(rs.getString(3)),
                                rs.getString(4),
                                rs.getString(5),
                                primaryKeys.contains(name),
                                StrUtil.containsIgnoreCase(rs.getString(6), "auto_increment")
                        ));
                    }
                }
            }
            return columns;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.ETL_DATASOURCE_CONNECT_FAIL,
                    "读取表结构失败: " + e.getMessage());
        }
    }

    /**
     * 按映射结果变更目标表：表不存在则新建，存在则增删列、改列类型并调整主键。
     *
     * @return 实际执行的 SQL 列表
     */
    public Map<String, Object> apply(ApplyRequest request) {
        String tableName = validateTable(request.getTable());
        if (request.getAddColumns() == null) {
            request.setAddColumns(Collections.emptyList());
        }
        if (request.getModifyColumns() == null) {
            request.setModifyColumns(Collections.emptyList());
        }
        if (request.getDropColumns() == null) {
            request.setDropColumns(Collections.emptyList());
        }
        if (request.getPrimaryKeys() == null) {
            request.setPrimaryKeys(Collections.emptyList());
        }
        if (request.getAddColumns().isEmpty() && request.getModifyColumns().isEmpty()
                && request.getDropColumns().isEmpty()
                && request.getPrimaryKeys().isEmpty() && !request.isDropPrimaryKey()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "没有需要变更的内容");
        }
        for (ColumnDef def : request.getAddColumns()) {
            validateIdent(def.getName(), "列名");
            def.setType(FieldMetaResolver.normalizeType(def.getType()));
        }
        for (ColumnDef def : request.getModifyColumns()) {
            validateIdent(def.getName(), "列名");
            def.setType(FieldMetaResolver.normalizeType(def.getType()));
        }
        for (String column : request.getDropColumns()) {
            validateIdent(column, "列名");
        }
        for (String key : request.getPrimaryKeys()) {
            validateIdent(key, "主键列名");
        }

        DataSource ds = datasourceService.getDataSource(request.getDatasourceId());
        List<String> executed = new ArrayList<>();

        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            boolean exists = tableExists(conn, tableName);

            if (!exists) {
                String ddl = buildCreateTable(tableName, request.getAddColumns(), request.getPrimaryKeys());
                log.info("创建目标表: {}", ddl);
                stmt.execute(ddl);
                executed.add(ddl);
                return result(tableName, true, executed);
            }

            // 主键为空表示"不改主键"，避免用户没勾选就被误删
            Set<String> currentKeys = currentPrimaryKeys(conn, tableName);
            boolean dropKeys = request.isDropPrimaryKey() && !currentKeys.isEmpty();
            boolean changePrimaryKey = !request.getPrimaryKeys().isEmpty()
                    && !sameKeys(currentKeys, request.getPrimaryKeys());

            // 合成一条 ALTER：多条 DDL 中途失败会留下半成品，MySQL 8 的单条 DDL 是原子的
            List<String> clauses = new ArrayList<>();
            if (dropKeys || (changePrimaryKey && !currentKeys.isEmpty())) {
                clauses.add("DROP PRIMARY KEY");
            }
            // 改类型放在加主键之前：TEXT 列不先转成 VARCHAR 无法做索引
            Set<String> modified = new HashSet<>();
            for (ColumnDef def : request.getModifyColumns()) {
                modified.add(def.getName().toLowerCase());
                StringBuilder clause = new StringBuilder("MODIFY COLUMN ")
                        .append(quote(def.getName())).append(" ").append(def.getType())
                        .append(Boolean.FALSE.equals(def.getNullable()) ? " NOT NULL" : " NULL");
                if (StrUtil.isNotBlank(def.getComment())) {
                    clause.append(" COMMENT '").append(def.getComment().replace("'", "''")).append("'");
                }
                clauses.add(clause.toString());
            }
            for (ColumnDef def : request.getAddColumns()) {
                // 新增列同时设为主键时必须声明 NOT NULL：MySQL 对主键列强制非空，
                // 声明 NULL 会让整条 ALTER 报 1171 并整体回滚（MySQL 8.4 实测）
                boolean asPrimaryKey = request.getPrimaryKeys().stream()
                        .anyMatch(key -> key.equalsIgnoreCase(def.getName()));
                StringBuilder clause = new StringBuilder("ADD COLUMN ")
                        .append(quote(def.getName())).append(" ").append(def.getType())
                        .append(asPrimaryKey ? " NOT NULL" : " NULL");
                if (StrUtil.isNotBlank(def.getComment())) {
                    clause.append(" COMMENT '").append(def.getComment().replace("'", "''")).append("'");
                }
                clauses.add(clause.toString());
            }
            for (String column : request.getDropColumns()) {
                clauses.add("DROP COLUMN " + quote(column));
            }
            if (changePrimaryKey) {
                // 主键列必须 NOT NULL；已有类型没改的列补一条 MODIFY，避免 ADD PRIMARY KEY 失败
                Map<String, String> currentDefs = currentColumnDefs(conn, tableName);
                for (String key : request.getPrimaryKeys()) {
                    if (modified.contains(key.toLowerCase())) {
                        continue;
                    }
                    String def = currentDefs.get(key.toLowerCase());
                    if (def != null) {
                        clauses.add("MODIFY COLUMN " + quote(key) + " " + def + " NOT NULL");
                    }
                }
                clauses.add("ADD PRIMARY KEY ("
                        + request.getPrimaryKeys().stream().map(this::quote).collect(Collectors.joining(", "))
                        + ")");
            }

            if (clauses.isEmpty()) {
                return result(tableName, false, executed);
            }

            String sql = "ALTER TABLE " + quote(tableName) + "\n  " + String.join(",\n  ", clauses);
            log.info("变更目标表结构: {}", sql);
            stmt.execute(sql);
            executed.add(sql);

            return result(tableName, false, executed);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("目标表结构变更失败: {}", e.getMessage(), e);
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                    "目标表结构变更失败: " + e.getMessage()
                            + (executed.isEmpty() ? "" : "；已执行 " + executed.size() + " 条语句，请核对表结构"));
        }
    }

    private Map<String, Object> result(String tableName, boolean created, List<String> executed) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("table", tableName);
        res.put("created", created);
        res.put("executedSql", executed);
        res.put("statements", executed.size());
        return res;
    }

    private String buildCreateTable(String tableName, List<ColumnDef> columns, List<String> primaryKeys) {
        if (columns.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标表不存在，且映射中没有可建表的字段");
        }
        // 主键列必须是 NOT NULL，否则 CREATE TABLE 会直接报错
        Set<String> keySet = primaryKeys.stream()
                .map(k -> k.toLowerCase())
                .collect(Collectors.toSet());

        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(tableName)).append(" (\n");
        for (ColumnDef def : columns) {
            boolean isKey = keySet.contains(def.getName().toLowerCase());
            ddl.append("  ").append(quote(def.getName())).append(" ").append(def.getType())
                    .append(isKey ? " NOT NULL" : " NULL").append(",\n");
        }
        if (!primaryKeys.isEmpty()) {
            ddl.append("  PRIMARY KEY (")
                    .append(primaryKeys.stream().map(this::quote).collect(Collectors.joining(", ")))
                    .append("),\n");
        }
        ddl.setLength(ddl.length() - 2);
        // 字段多时 COMPACT 行格式会报 "Row size too large"，显式指定 DYNAMIC
        ddl.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC");
        return ddl.toString();
    }

    private Set<String> currentPrimaryKeys(Connection conn, String tableName) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' "
                        + "ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        return keys;
    }

    private boolean sameKeys(Set<String> current, List<String> desired) {
        if (current.size() != desired.size()) {
            return false;
        }
        Set<String> lower = current.stream().map(String::toLowerCase).collect(Collectors.toSet());
        return desired.stream().allMatch(k -> lower.contains(k.toLowerCase()));
    }

    /**
     * 现有列的定义片段（类型 + 注释 + 自增），拼 MODIFY 时用来保留原有信息
     */
    private Map<String, String> currentColumnDefs(Connection conn, String tableName) throws Exception {
        Map<String, String> defs = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, EXTRA FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder def = new StringBuilder(rs.getString(2));
                    if (StrUtil.containsIgnoreCase(rs.getString(4), "auto_increment")) {
                        def.append(" AUTO_INCREMENT");
                    }
                    String comment = rs.getString(3);
                    if (StrUtil.isNotBlank(comment)) {
                        def.append(" COMMENT '").append(comment.replace("'", "''")).append("'");
                    }
                    defs.put(rs.getString(1).toLowerCase(), def.toString());
                }
            }
        }
        return defs;
    }

    private boolean tableExists(Connection conn, String tableName) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private String validateTable(String table) {
        String value = StrUtil.trimToEmpty(table);
        if (StrUtil.isBlank(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标表名不能为空");
        }
        // 支持 db.table 形式，但库名不做变更操作，仅允许合法标识符
        int dot = value.indexOf('.');
        if (dot > 0) {
            validateIdent(value.substring(0, dot), "库名");
            validateIdent(value.substring(dot + 1), "表名");
        } else {
            validateIdent(value, "表名");
        }
        return value;
    }

    private void validateIdent(String ident, String label) {
        if (StrUtil.isBlank(ident) || !IDENT.matcher(ident.trim()).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    label + "不合法：" + ident + "（只允许字母、数字、下划线，且不以数字开头）");
        }
    }

    private String quote(String ident) {
        String value = StrUtil.trimToEmpty(ident);
        int dot = value.indexOf('.');
        if (dot > 0) {
            return "`" + value.substring(0, dot).replace("`", "``") + "`.`"
                    + value.substring(dot + 1).replace("`", "``") + "`";
        }
        return "`" + value.replace("`", "``") + "`";
    }

    // ===== 数据结构 =====

    @Getter
    public static class TableColumn {
        private final String name;
        private final String columnType;
        private final boolean nullable;
        private final String defaultValue;
        private final String comment;
        private final boolean primaryKey;
        private final boolean autoIncrement;

        public TableColumn(String name, String columnType, boolean nullable, String defaultValue,
                           String comment, boolean primaryKey, boolean autoIncrement) {
            this.name = name;
            this.columnType = columnType;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.primaryKey = primaryKey;
            this.autoIncrement = autoIncrement;
        }
    }

    @lombok.Data
    public static class ColumnDef {
        private String name;
        private String type;
        private String comment;
        /** 仅改类型时使用：是否允许 NULL，未指定按可空处理 */
        private Boolean nullable;
    }

    @lombok.Data
    public static class ApplyRequest {
        private Long datasourceId;
        private String table;
        private List<ColumnDef> addColumns;
        /** 已有列改类型（MODIFY COLUMN） */
        private List<ColumnDef> modifyColumns;
        private List<String> dropColumns;
        private List<String> primaryKeys;
        private boolean dropPrimaryKey;
    }
}
