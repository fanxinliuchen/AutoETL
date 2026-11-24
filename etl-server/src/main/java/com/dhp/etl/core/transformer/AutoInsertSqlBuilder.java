package com.dhp.etl.core.transformer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.extractor.RequestVarResolver;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.WriteModeEnum;
import com.dhp.etl.service.EtlDatasourceService;
import lombok.Getter;
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
    private final RequestVarResolver requestVarResolver;

    /**
     * 生成直通入库脚本
     *
     * @param pipeline  当前流程（需已配置目标数据源与目标表）
     * @param stageTable 临时表名
     * @param logBuffer  执行日志
     */
    public String build(EtlPipeline pipeline, String stageTable, StringBuilder logBuffer) {
        return build(pipeline, stageTable, logBuffer, Collections.emptyMap());
    }

    /**
     * 增量同步（SYNC）比对键，映射到临时表的实际列名。
     *
     * <p>软删除的 NOT EXISTS 会拿这些列去临时表探针：临时表只有自增 `_stage_id` 主键，
     * 不改的话每行都要全表扫一遍（28 万 × 29 万），所以写回前要按这组列建索引。
     *
     * @return 无主键 / 非数据库目标 / 键列不在临时表中时返回空集合
     */
    public List<String> stageKeyColumns(EtlPipeline pipeline, String stageTable) {
        String targetTable = StrUtil.trim(pipeline.getTargetTable());
        if (StrUtil.isBlank(targetTable) || pipeline.getTargetDsId() == null || StrUtil.isBlank(stageTable)) {
            return List.of();
        }
        try (Connection conn = datasourceService.getDataSource(pipeline.getTargetDsId()).getConnection()) {
            List<String> keys = primaryKeyColumns(conn, targetTable);
            if (keys.isEmpty()) {
                return List.of();
            }
            Map<String, String> stageIndex = new LinkedHashMap<>();
            for (String column : columns(conn, stageTable)) {
                if (!STAGE_ID_COLUMN.equalsIgnoreCase(column)) {
                    stageIndex.put(column.toLowerCase(), column);
                }
            }
            List<String> result = new ArrayList<>();
            for (String key : keys) {
                String actual = stageIndex.get(key.toLowerCase());
                if (actual != null) {
                    result.add(actual);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析临时表比对键失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * @param loopVars 循环取数当前轮的参数，用于解析增量同步范围里的 ${变量}
     */
    public String build(EtlPipeline pipeline, String stageTable, StringBuilder logBuffer,
                        Map<String, String> loopVars) {
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

            WriteModeEnum mode = pipeline.getWriteMode() == null
                    ? WriteModeEnum.APPEND : pipeline.getWriteMode();
            // UPSERT 的冲突键直接取目标表真实主键，界面上不再手填
            List<String> targetKeys = mode == WriteModeEnum.SYNC ? List.of() : primaryKeyColumns(conn, targetTable);
            String sql = mode == WriteModeEnum.SYNC
                    ? buildSync(conn, pipeline, targetTable, stageTable, targetColumns, matched, stageIndex, logBuffer, loopVars)
                    : assemble(pipeline, targetTable, stageTable, matched, stageIndex, targetKeys);
            if (mode == WriteModeEnum.UPSERT && targetKeys.isEmpty() && logBuffer != null) {
                logBuffer.append("[处理] 提示：目标表没有主键，UPSERT 会退化为追加插入，重复运行会产生重复行。"
                        + "可在第 4 步「更新（新建）目标表」里把业务唯一列设为主键\n");
            }
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

    /**
     * 增量同步：范围内「目标表有、本次没抽到」的行打软删除标记，其余按主键 UPSERT
     */
    private String buildSync(Connection conn,
                             EtlPipeline pipeline,
                             String targetTable,
                             String stageTable,
                             List<String> targetColumns,
                             List<String> matched,
                             Map<String, String> stageIndex,
                             StringBuilder logBuffer,
                             Map<String, String> loopVars) throws Exception {
        SyncConfig config = resolveScope(parseSyncConfig(pipeline.getSyncConfig()), pipeline, logBuffer, loopVars);

        // 软删除控制列必须已存在于目标表
        requireColumn(targetColumns, config.getSoftDeleteColumn(), "软删除标记列");
        if (StrUtil.isNotBlank(config.getSoftDeleteTimeColumn())) {
            requireColumn(targetColumns, config.getSoftDeleteTimeColumn(), "软删除时间列");
        }
        requireColumn(targetColumns, config.getScopeField(), "删除判定范围字段");

        // 比对主键直接取目标表真实主键：软删除靠「目标表有、本次没抽到」倒推，没有主键就无从比对
        Set<String> keys = primaryKeyColumns(conn, targetTable).stream()
                .map(String::toLowerCase)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keys.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "增量同步需要目标表有主键：系统靠「目标表有、本次没抽到」倒推删除，没有主键无从比对。"
                            + "请先在「更新（新建）目标表」里把业务唯一列设为主键");
        }
        for (String key : keys) {
            if (matched.stream().noneMatch(c -> c.equalsIgnoreCase(key))) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "主键列 " + key + " 不在「目标表 ∩ 抽取字段」中，无法用于比对。"
                                + "请确认该字段已抽取且目标表存在同名列");
            }
        }
        if (logBuffer != null) {
            logBuffer.append("[同步] 比对主键（取自目标表主键）: ").append(String.join(", ", keys)).append("\n");
        }

        // 熔断：待删行数占范围内行数比例过高时中止，避免范围填错导致大面积误标
        long scopeTotal = countScope(conn, targetTable, config);
        long toDelete = countToDelete(conn, targetTable, stageTable, matched, stageIndex, config, keys);
        double ratio = scopeTotal == 0 ? 0 : (double) toDelete / scopeTotal;
        if (logBuffer != null) {
            logBuffer.append(String.format("[同步] 删除判定范围：%s，范围内 %d 行，本次判定删除 %d 行（%.1f%%）\n",
                    config.describeScope(), scopeTotal, toDelete, ratio * 100));
        }
        if (toDelete > 0 && ratio > config.getMaxDeleteRatio()) {
            throw new BizException(ErrorCode.PARAM_ERROR, String.format(
                    "增量同步中止：本次判定删除 %d 行，占范围内 %d 行的 %.1f%%，超过阈值 %.0f%%。"
                            + "请检查「范围字段 / 范围起点终点」是否配置正确，或确认后可上调删除比例上限（maxDeleteRatio）",
                    toDelete, scopeTotal, ratio * 100, config.getMaxDeleteRatio() * 100));
        }

        if (logBuffer != null) {
            logBuffer.append("[处理] 写入模式：增量同步（软删除标记 + UPSERT）\n");
        }
        return assembleSync(pipeline, targetTable, stageTable, matched, stageIndex, config, keys);
    }

    /**
     * 把「范围起点 / 范围终点」里的 ${变量} 解析成实际取值。
     *
     * <p>变量来源与第 2 步一致：变量定义（在目标数据源上实时计算）、循环取数当轮参数、内置的
     * today / yesterday / now。解析在每轮开始时做一次，判定范围用的 UPDATE 与两条 COUNT 共用
     * 同一份结果，避免同一次运行里算出不同的区间。
     */
    SyncConfig resolveScope(SyncConfig config,
                            EtlPipeline pipeline,
                            StringBuilder logBuffer,
                            Map<String, String> loopVars) {
        String startExpr = renderBound(requestVarResolver.resolve(pipeline, prepareBound(config.getScopeStart()), logBuffer, loopVars));
        String endExpr = renderBound(requestVarResolver.resolve(pipeline, prepareBound(config.getScopeEnd()), logBuffer, loopVars));

        if (logBuffer != null) {
            if (StrUtil.isNotBlank(config.getScopeStart()) || StrUtil.isNotBlank(config.getScopeEnd())) {
                logBuffer.append("[同步] 范围解析：起点 ")
                        .append(StrUtil.isBlank(config.getScopeStart()) ? "（不限）"
                                : config.getScopeStart() + " → " + StrUtil.blankToDefault(startExpr, "（不限）"))
                        .append("，终点 ")
                        .append(StrUtil.isBlank(config.getScopeEnd()) ? "（不限）"
                                : config.getScopeEnd() + " → " + StrUtil.blankToDefault(endExpr, "（不限）"))
                        .append("\n");
            }
        }
        return config.withScopeExpr(startExpr, endExpr);
    }

    /**
     * 变量替换前的预处理。
     *
     * <p>整个取值就是一个 <code>${变量}</code> 时保持原样，由 {@link #renderBound(String)} 统一加引号；
     * 取值是 SQL 表达式（含括号）时，先把 <code>${变量}</code> 包成 <code>'${变量}'</code>——
     * 直接替换会得到 {@code DATE_SUB(2026-11-10, INTERVAL 1 DAY)} 这种日期不带引号的写法，MySQL 会当减法算。
     */
    static String prepareBound(String raw) {
        String value = StrUtil.trimToEmpty(raw);
        if (StrUtil.isBlank(value) || !value.contains("${") || !value.contains("(")) {
            return value;
        }
        java.util.regex.Matcher matcher = SCOPE_VAR_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer,
                    java.util.regex.Matcher.quoteReplacement("'" + matcher.group() + "'"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 把范围取值渲染成 SQL 片段：
     * <ul>
     *   <li>含括号 → 原生 SQL 表达式，原样使用（如 {@code DATE_SUB(CURDATE(), INTERVAL 90 DAY)}、旧版窗口天数）</li>
     *   <li>纯数字 → 数字常量，不加引号（日期字段常见存 20261101 这类整数）</li>
     *   <li>其余 → 文本常量，按日期/时间字符串加引号</li>
     * </ul>
     */
    static String renderBound(String raw) {
        String value = StrUtil.trimToEmpty(raw);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        if (value.contains("(") || value.contains(")")) {
            return value;
        }
        if (NUMERIC_PATTERN.matcher(value).matches()) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 生成增量同步脚本：先软删除本次缺失的数据，再 UPSERT 本次数据
     */
    static String assembleSync(EtlPipeline pipeline,
                               String targetTable,
                               String stageTable,
                               List<String> matched,
                               Map<String, String> stageIndex,
                               SyncConfig config,
                               Set<String> keyLower) {
        String targetRef = tableRef(targetTable);
        String stageRef = "`" + escape(stageTable) + "`";
        String softDeleteColumn = config.getSoftDeleteColumn();
        String softDeleteTimeColumn = config.getSoftDeleteTimeColumn();

        // 业务列：排除由系统控制的软删除列，避免和抽取字段重名冲突
        List<String> dataColumns = matched.stream()
                .filter(c -> !c.equalsIgnoreCase(softDeleteColumn)
                        && (StrUtil.isBlank(softDeleteTimeColumn)
                        || !c.equalsIgnoreCase(softDeleteTimeColumn)))
                .toList();

        // 比对主键（取目标表中的实际列名）
        List<String> keyColumns = keyLower.stream()
                .map(key -> actualColumn(matched, key))
                .filter(Objects::nonNull)
                .toList();

        StringBuilder sql = new StringBuilder();

        // ① 软删除：范围内、当前未标记删除、但本次抽取结果里已不存在
        String scopeCondition = config.scopeCondition("o");
        sql.append("UPDATE ").append(targetRef).append(" o\n")
                .append("SET o.`").append(escape(softDeleteColumn)).append("` = 1");
        if (StrUtil.isNotBlank(softDeleteTimeColumn)) {
            sql.append(", o.`").append(escape(softDeleteTimeColumn)).append("` = NOW()");
        }
        sql.append("\nWHERE ");
        if (StrUtil.isNotBlank(scopeCondition)) {
            sql.append(scopeCondition).append("\n  AND ");
        }
        sql.append("(o.`").append(escape(softDeleteColumn)).append("` = 0 OR o.`")
                .append(escape(softDeleteColumn)).append("` IS NULL)\n")
                .append("  AND NOT EXISTS (SELECT 1 FROM ").append(stageRef).append(" s WHERE ")
                .append(keyColumns.stream()
                        .map(c -> "s.`" + escape(stageIndex.get(c.toLowerCase())) + "` = o.`" + escape(c) + "`")
                        .collect(Collectors.joining(" AND ")))
                .append(");\n");

        // ② 合并：本次数据写入，已存在则更新，并把软删除标记复位（数据又出现了）
        List<String> insertColumns = new ArrayList<>(dataColumns);
        List<String> selectValues = dataColumns.stream()
                .map(c -> "`" + escape(stageIndex.get(c.toLowerCase())) + "`")
                .collect(Collectors.toCollection(ArrayList::new));
        insertColumns.add(softDeleteColumn);
        selectValues.add("0");
        if (StrUtil.isNotBlank(softDeleteTimeColumn)) {
            insertColumns.add(softDeleteTimeColumn);
            selectValues.add("NULL");
        }

        sql.append("INSERT INTO ").append(targetRef).append(" (")
                .append(insertColumns.stream().map(c -> "`" + escape(c) + "`").collect(Collectors.joining(", ")))
                .append(")\nSELECT ")
                .append(String.join(", ", selectValues))
                .append(" FROM ").append(stageRef);

        List<String> updateColumns = dataColumns.stream()
                .filter(c -> !keyLower.contains(c.toLowerCase()))
                .toList();
        List<String> assignments = new ArrayList<>(updateColumns.stream()
                .map(c -> "`" + escape(c) + "`=VALUES(`" + escape(c) + "`)")
                .toList());
        assignments.add("`" + escape(softDeleteColumn) + "`=0");
        if (StrUtil.isNotBlank(softDeleteTimeColumn)) {
            assignments.add("`" + escape(softDeleteTimeColumn) + "`=NULL");
        }
        if (assignments.isEmpty()) {
            assignments.add("`" + escape(keyColumns.get(0)) + "`=VALUES(`" + escape(keyColumns.get(0)) + "`)");
        }
        sql.append("\nON DUPLICATE KEY UPDATE ").append(String.join(", ", assignments));
        return sql.toString();
    }

    static String assemble(EtlPipeline pipeline,
                            String targetTable,
                            String stageTable,
                            List<String> matched,
                            Map<String, String> stageIndex,
                            List<String> targetKeyColumns) {
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
            // 冲突判定由 MySQL 按目标表真实的主键/唯一索引完成，这里只需把主键列排除在 UPDATE 之外
            Set<String> keys = targetKeyColumns.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
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

    // ===== 增量同步（SYNC）辅助 =====

    private static final java.util.regex.Pattern IDENT_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** 纯数字常量（不加引号，兼容把日期存成 20261101 这类整数的字段） */
    private static final java.util.regex.Pattern NUMERIC_PATTERN =
            java.util.regex.Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /** 匹配 ${变量名}，用于把表达式里的变量占位先包成字符串字面量 */
    private static final java.util.regex.Pattern SCOPE_VAR_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{[^}]{1,64}}");

    /** 范围取值原文长度上限（避免把整段脚本塞进来） */
    private static final int MAX_SCOPE_LENGTH = 200;

    /**
     * 解析增量同步配置
     */
    static SyncConfig parseSyncConfig(String json) {
        if (StrUtil.isBlank(json)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "增量同步模式需要先配置：删除判定范围字段（如 FAuditDate）、范围起点、软删除标记列");
        }
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "增量同步配置不是合法 JSON: " + e.getMessage());
        }

        String scopeField = StrUtil.trimToEmpty(obj.getStr("scopeField"));
        if (StrUtil.isBlank(scopeField)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "请填写删除判定范围字段（金蝶常见为审核时间 FAuditDate）：金蝶物理删除后无法回查，"
                            + "只能在该字段的范围内比对「目标表有、本次没抽到」来推断删除");
        }
        if (!IDENT_PATTERN.matcher(scopeField).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "范围字段名不合法: " + scopeField);
        }

        // 范围：起点 >= / 终点 <=，各自可以是常量、${变量} 或原生 SQL 表达式
        String scopeStart = StrUtil.trimToEmpty(obj.getStr("scopeStart"));
        String scopeEnd = StrUtil.trimToEmpty(obj.getStr("scopeEnd"));
        if (StrUtil.isBlank(scopeStart) && StrUtil.isBlank(scopeEnd)) {
            // 兼容旧配置：只有窗口天数时折算成「近 N 天」的起点，终点不限
            int legacyDays = obj.getInt("scopeDays", 0);
            if (legacyDays > 0 && legacyDays <= 3650) {
                scopeStart = "DATE_SUB(CURDATE(), INTERVAL " + legacyDays + " DAY)";
            }
        }
        if (StrUtil.isBlank(scopeStart) && StrUtil.isBlank(scopeEnd)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "请填写删除判定范围起点（如变量 ${begindate} 或常量 2026-11-01）：范围决定比对哪些行，"
                            + "留空会把整张目标表纳入判定");
        }
        validateScopeBound(scopeStart, "范围起点");
        validateScopeBound(scopeEnd, "范围终点");

        String softDeleteColumn = StrUtil.trimToEmpty(obj.getStr("softDeleteColumn"));
        if (StrUtil.isBlank(softDeleteColumn)) {
            softDeleteColumn = "is_deleted";
        }
        if (!IDENT_PATTERN.matcher(softDeleteColumn).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "软删除标记列名不合法: " + softDeleteColumn);
        }

        // 显式传空串表示不需要时间列；未传则用默认名
        String softDeleteTimeColumn = obj.containsKey("softDeleteTimeColumn")
                ? StrUtil.trimToEmpty(obj.getStr("softDeleteTimeColumn"))
                : "delete_time";
        if (StrUtil.isNotBlank(softDeleteTimeColumn)
                && !IDENT_PATTERN.matcher(softDeleteTimeColumn).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "软删除时间列名不合法: " + softDeleteTimeColumn);
        }

        double maxDeleteRatio = obj.getDouble("maxDeleteRatio", 0.5);
        if (maxDeleteRatio <= 0 || maxDeleteRatio > 1) {
            maxDeleteRatio = 0.5;
        }
        return new SyncConfig(scopeField, scopeStart, scopeEnd, softDeleteColumn, softDeleteTimeColumn, maxDeleteRatio);
    }

    private static void validateScopeBound(String value, String label) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        if (value.length() > MAX_SCOPE_LENGTH) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    label + "过长（上限 " + MAX_SCOPE_LENGTH + " 字符），只允许常量、${变量} 或单条 SQL 表达式");
        }
        if (value.contains(";")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    label + "只允许单个取值，不支持分号分隔多语句");
        }
    }

    private void requireColumn(List<String> targetColumns, String column, String label) {
        boolean exists = targetColumns.stream().anyMatch(c -> c.equalsIgnoreCase(column));
        if (!exists) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "目标表缺少" + label + " `" + column + "`。增量同步需要它来标记删除，"
                            + "请先在目标表添加该列（如 `is_deleted` TINYINT DEFAULT 0、`delete_time` DATETIME NULL）");
        }
    }

    private long countScope(Connection conn, String targetTable, SyncConfig config) throws Exception {
        String scopeCondition = config.scopeCondition(null);
        String sql = "SELECT COUNT(*) FROM " + tableRef(targetTable)
                + (StrUtil.isBlank(scopeCondition) ? "" : " WHERE " + scopeCondition);
        return queryLong(conn, sql);
    }

    private long countToDelete(Connection conn,
                               String targetTable,
                               String stageTable,
                               List<String> matched,
                               Map<String, String> stageIndex,
                               SyncConfig config,
                               Set<String> keyLower) throws Exception {
        String keyJoin = keyLower.stream()
                .map(key -> {
                    String targetColumn = actualColumn(matched, key);
                    String stageColumn = stageIndex.get(targetColumn.toLowerCase());
                    return "s.`" + escape(stageColumn) + "` = o.`" + escape(targetColumn) + "`";
                })
                .collect(Collectors.joining(" AND "));

        String scopeCondition = config.scopeCondition("o");
        String sql = "SELECT COUNT(*) FROM " + tableRef(targetTable) + " o"
                + " WHERE "
                + (StrUtil.isBlank(scopeCondition) ? "" : scopeCondition + " AND ")
                + "(o.`" + escape(config.getSoftDeleteColumn()) + "` = 0 OR o.`"
                + escape(config.getSoftDeleteColumn()) + "` IS NULL)"
                + " AND NOT EXISTS (SELECT 1 FROM `" + escape(stageTable) + "` s WHERE " + keyJoin + ")";
        return queryLong(conn, sql);
    }

    private long queryLong(Connection conn, String sql) throws Exception {
        try (java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * 在已匹配列中按忽略大小写找到实际列名
     */
    static String actualColumn(List<String> matched, String keyLower) {
        for (String column : matched) {
            if (column.equalsIgnoreCase(keyLower)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 增量同步配置
     */
    @Getter
    public static class SyncConfig {
        /** 删除判定范围字段（通常是审核时间） */
        private final String scopeField;
        /** 范围起点原文（常量 / ${变量} / SQL 表达式），对应「范围字段 >= 起点」 */
        private final String scopeStart;
        /** 范围终点原文，对应「范围字段 < 终点」（开区间），可为空表示不设上限 */
        private final String scopeEnd;
        /** 起点渲染后的 SQL 片段（已解析变量、已加引号），为空表示不设下限 */
        private final String scopeStartExpr;
        /** 终点渲染后的 SQL 片段，为空表示不设上限 */
        private final String scopeEndExpr;
        /** 软删除标记列 */
        private final String softDeleteColumn;
        /** 软删除时间列，可为空 */
        private final String softDeleteTimeColumn;
        /** 删除比例上限，超过则中止 */
        private final double maxDeleteRatio;

        public SyncConfig(String scopeField, String scopeStart, String scopeEnd,
                          String softDeleteColumn, String softDeleteTimeColumn, double maxDeleteRatio) {
            this(scopeField, scopeStart, scopeEnd, null, null,
                    softDeleteColumn, softDeleteTimeColumn, maxDeleteRatio);
        }

        private SyncConfig(String scopeField, String scopeStart, String scopeEnd,
                           String scopeStartExpr, String scopeEndExpr,
                           String softDeleteColumn, String softDeleteTimeColumn, double maxDeleteRatio) {
            this.scopeField = scopeField;
            this.scopeStart = scopeStart;
            this.scopeEnd = scopeEnd;
            this.scopeStartExpr = scopeStartExpr;
            this.scopeEndExpr = scopeEndExpr;
            this.softDeleteColumn = softDeleteColumn;
            this.softDeleteTimeColumn = softDeleteTimeColumn;
            this.maxDeleteRatio = maxDeleteRatio;
        }

        /**
         * 变量解析完成后回填渲染好的片段
         */
        SyncConfig withScopeExpr(String startExpr, String endExpr) {
            return new SyncConfig(scopeField, scopeStart, scopeEnd, startExpr, endExpr,
                    softDeleteColumn, softDeleteTimeColumn, maxDeleteRatio);
        }

        /**
         * 生成「范围字段 >= 起点 AND 范围字段 < 终点」条件；两侧都为空时返回空串
         *
         * <p>终点是开区间：会计区间惯例是左闭右开，日期串直接比较也不会漏掉终点当天的数据。
         *
         * @param alias 表别名（如 {@code o}），为空时不带前缀
         */
        String scopeCondition(String alias) {
            String prefix = StrUtil.isBlank(alias) ? "" : alias + ".";
            String column = prefix + "`" + escape(scopeField) + "`";
            String start = resolveExpr(scopeStartExpr, scopeStart);
            String end = resolveExpr(scopeEndExpr, scopeEnd);
            List<String> parts = new ArrayList<>();
            if (StrUtil.isNotBlank(start)) {
                parts.add(column + " >= " + start);
            }
            if (StrUtil.isNotBlank(end)) {
                parts.add(column + " < " + end);
            }
            return String.join(" AND ", parts);
        }

        /**
         * 日志展示用：不带别名的范围条件
         */
        String describeScope() {
            String condition = scopeCondition(null);
            return StrUtil.isBlank(condition) ? "未设限（整表）" : condition;
        }

        private static String resolveExpr(String expr, String raw) {
            return StrUtil.isNotBlank(expr) ? expr : renderBound(raw);
        }
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

    /**
     * 目标表主键列（原始大小写）。UPSERT 用它把主键列排除在 ON DUPLICATE KEY UPDATE 之外——
     * 冲突判定本身由 MySQL 按表上真实的主键/唯一索引完成，不需要界面再填一遍。
     */
    private List<String> primaryKeyColumns(Connection conn, String table) throws Exception {
        TableRef ref = TableRef.of(table);
        String sql = "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                + "WHERE TABLE_SCHEMA = " + (ref.schema == null ? "DATABASE()" : "?")
                + " AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY ORDINAL_POSITION";
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
