package com.dhp.etl.core.transformer;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.WriteModeEnum;
import com.dhp.etl.service.EtlDatasourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 未配置转换 SQL 时，按目标表结构自动生成直通入库脚本。
 *
 * <p>只在「第 3 步 SQL 转换处理」留空时生效。生成规则：
 * <ol>
 *   <li>读取目标表与临时表的实际列（information_schema）</li>
 *   <li>按列名取交集，<b>显式列出列名</b>而不是 SELECT *——两边列顺序不一致时不会串列</li>
 *   <li>按写入模式决定是否先 TRUNCATE，UPSERT 则追加 ON DUPLICATE KEY UPDATE</li>
 * </ol>
 *
 * <p>目标表与抽取字段没有同名列时直接报错，不会静默写入空数据或错列数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoInsertSqlBuilder {

    private static final String STAGE_ID_COLUMN = "_stage_id";

    private final EtlDatasourceService datasourceService;

    /**
     * 生成直通入库脚本
     *
     * @param pipeline  当前流程（需已配置目标数据源与目标表）
     * @param stageTable 临时表名
     * @param logBuffer  执行日志
     */
    public String build(EtlPipeline pipeline, String stageTable, StringBuilder logBuffer) {
        String targetTable = StrUtil.trim(pipeline.getTargetTable());
        if (StrUtil.isBlank(targetTable)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未配置目标表，无法自动生成入库脚本");
        }
        if (pipeline.getTargetDsId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未配置目标数据源，无法自动生成入库脚本");
        }

        DataSource dataSource = datasourceService.getDataSource(pipeline.getTargetDsId());
        try (Connection conn = dataSource.getConnection()) {
            List<String> targetColumns = columns(conn, targetTable);
            if (targetColumns.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "目标表 " + targetTable + " 不存在或没有任何字段。请先建表，或在第 3 步填写转换 SQL");
            }

            List<String> stageColumns = columns(conn, stageTable);
            if (stageColumns.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "临时表 " + stageTable + " 尚未创建（本次可能未抽取到数据），无法自动生成入库脚本");
            }

            // 临时表列：小写 -> 实际列名
            Map<String, String> stageIndex = new LinkedHashMap<>();
            for (String column : stageColumns) {
                if (STAGE_ID_COLUMN.equalsIgnoreCase(column)) {
                    continue;
                }
                stageIndex.put(column.toLowerCase(), column);
            }

            // 按目标表列顺序取交集
            List<String> matched = new ArrayList<>();
            for (String column : targetColumns) {
                if (stageIndex.containsKey(column.toLowerCase())) {
                    matched.add(column);
                }
            }
            if (matched.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "目标表与抽取字段没有同名列，无法自动生成入库脚本。"
                                + "目标表列: " + brief(targetColumns)
                                + "；抽取列: " + brief(new ArrayList<>(stageIndex.values()))
                                + "。请在第 3 步填写转换 SQL 做字段映射");
            }

            // 目标表 NOT NULL 且无默认值的列如果没被覆盖，入库很可能失败，提前提示
            List<String> missingRequired = requiredColumns(conn, targetTable);
            missingRequired.removeAll(matched);
            if (!missingRequired.isEmpty() && logBuffer != null) {
                logBuffer.append("[处理] 提示：目标表以下列为 NOT NULL 且无默认值，但抽取结果里没有，"
                        + "入库可能失败（可为其配置默认值或补字段）: ").append(missingRequired).append("\n");
            }

            String sql = assemble(pipeline, targetTable, stageTable, matched, stageIndex);
            if (logBuffer != null) {
                logBuffer.append("[处理] 未配置转换 SQL，已按目标表结构自动生成入库脚本（")
                        .append(matched.size()).append(" 列）\n");
            }
            return sql;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("自动生成入库脚本失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "自动生成入库脚本失败: " + e.getMessage());
        }
    }

    static String assemble(EtlPipeline pipeline,
                            String targetTable,
                            String stageTable,
                            List<String> matched,
                            Map<String, String> stageIndex) {
        String targetRef = tableRef(targetTable);
        String columnList = matched.stream()
                .map(c -> "`" + escape(c) + "`")
                .collect(Collectors.joining(", "));
        String selectList = matched.stream()
                .map(c -> "`" + escape(stageIndex.get(c.toLowerCase())) + "`")
                .collect(Collectors.joining(", "));

        WriteModeEnum mode = pipeline.getWriteMode() == null
                ? WriteModeEnum.APPEND : pipeline.getWriteMode();

        StringBuilder sql = new StringBuilder();
        if (mode == WriteModeEnum.OVERWRITE) {
            sql.append("TRUNCATE TABLE ").append(targetRef).append(";\n");
        }

        sql.append("INSERT INTO ").append(targetRef)
                .append(" (").append(columnList).append(")\n")
                .append("SELECT ").append(selectList)
                .append(" FROM `").append(escape(stageTable)).append("`");

        if (mode == WriteModeEnum.UPSERT) {
            Set<String> keys = parseKeys(pipeline.getUpsertKeys());
            if (keys.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "写入模式为 UPSERT，但未配置业务主键。请在第 4 步填写主键字段，"
                                + "或改用「追加写入 / 清空覆盖」（自动生成脚本无法凭空推断冲突键）");
            }
            List<String> updateColumns = matched.stream()
                    .filter(c -> !keys.contains(c.toLowerCase()))
                    .toList();
            // 全部列都是主键时，用一个自赋值占位，避免生成空 UPDATE 子句
            if (updateColumns.isEmpty()) {
                updateColumns = List.of(matched.get(0));
            }
            sql.append("\nON DUPLICATE KEY UPDATE ").append(
                    updateColumns.stream()
                            .map(c -> "`" + escape(c) + "`=VALUES(`" + escape(c) + "`)")
                            .collect(Collectors.joining(", ")));
        }
        return sql.toString();
    }

    static Set<String> parseKeys(String upsertKeys) {
        if (StrUtil.isBlank(upsertKeys)) {
            return Collections.emptySet();
        }
        return Arrays.stream(upsertKeys.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(k -> k.toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 读取表的所有列名（按定义顺序）
     */
    private List<String> columns(Connection conn, String table) throws Exception {
        TableRef ref = TableRef.of(table);
        String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = " + (ref.schema == null ? "DATABASE()" : "?")
                + " AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (ref.schema != null) {
                ps.setString(index++, ref.schema);
            }
            ps.setString(index, ref.table);
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
            return result;
        }
    }

    /**
     * 目标表里 NOT NULL、无默认值且非自增的列——这些列不给值就会入库失败
     */
    private List<String> requiredColumns(Connection conn, String table) throws Exception {
        TableRef ref = TableRef.of(table);
        String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = " + (ref.schema == null ? "DATABASE()" : "?")
                + " AND TABLE_NAME = ? AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT IS NULL"
                + " AND EXTRA NOT LIKE '%auto_increment%' ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (ref.schema != null) {
                ps.setString(index++, ref.schema);
            }
            ps.setString(index, ref.table);
            List<String> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
            return result;
        }
    }

    static String tableRef(String table) {
        TableRef ref = TableRef.of(table);
        if (ref.schema == null) {
            return "`" + escape(ref.table) + "`";
        }
        return "`" + escape(ref.schema) + "`.`" + escape(ref.table) + "`";
    }

    static String brief(List<String> columns) {
        String joined = String.join(", ", columns);
        return columns.size() > 12 ? StrUtil.sub(joined, 0, 160) + " …共 " + columns.size() + " 列" : joined;
    }

    static String escape(String ident) {
        return ident == null ? "" : ident.replace("`", "``");
    }

    /**
     * 支持 db.table 形式的表名
     */
    private static final class TableRef {
        private final String schema;
        private final String table;

        private TableRef(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        static TableRef of(String raw) {
            String value = StrUtil.trim(raw);
            int dot = value.indexOf('.');
            if (dot > 0 && dot < value.length() - 1) {
                return new TableRef(value.substring(0, dot), value.substring(dot + 1));
            }
            return new TableRef(null, value);
        }
    }
}
