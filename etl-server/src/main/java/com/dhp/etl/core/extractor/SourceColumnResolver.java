package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.guard.PostgresSqlGuard;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.dto.SourceColumnQueryDTO;
import com.dhp.etl.dto.SourceColumnResultDTO;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.service.EtlPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 MySQL 抽取 SQL 的输出字段。
 *
 * <p>做法：把用户 SQL 包成 `SELECT * FROM ( <sql> ) __etl_meta LIMIT 1`
 * 执行一次，既拿到结果集元数据（列名/类型），又拿到 1 行样例值用于修正类型，
 * `LIMIT 1` 保证不会把大表整表拉出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceColumnResolver {

    /** 单条 SQL 的列数上限，防止误贴整库导出 */
    private static final int MAX_COLUMNS = 500;

    private static final int QUERY_TIMEOUT_SECONDS = 15;

    private static final Pattern UNRESOLVED_VAR = Pattern.compile("\\$\\{[^}]+}");

    private final EtlDatasourceService datasourceService;
    private final EtlPipelineService pipelineService;
    private final RequestVarResolver requestVarResolver;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final PostgresSqlGuard postgresSqlGuard;

    public SourceColumnResultDTO resolve(SourceColumnQueryDTO query) {
        if (query == null || query.getDatasourceId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请选择来源数据源");
        }
        String rawSql = StrUtil.trimToEmpty(query.getSql());
        if (StrUtil.isBlank(rawSql)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抽取 SQL 不能为空");
        }

        // 1. 变量替换：带 pipelineId 才能用流程里定义的自定义变量
        String sql = resolveVariables(query.getPipelineId(), rawSql);

        // 2. 安全校验：PG 方言过不了 JSqlParser，改用文本规则（运行时另有 EXPLAIN 兜底）
        if (isPostgresSource(query.getDatasourceId())) {
            postgresSqlGuard.validateShape(sql);
        } else {
            sqlSafetyGuard.validate(sql);
            ensureSingleSelect(sql);
        }

        String inner = stripTrailingSemicolon(sql);
        // 换行包裹：SQL 末尾若带 -- 行注释，也不会把闭合子查询的右括号注释掉
        String metaSql = "SELECT * FROM (\n" + inner + "\n) __etl_meta LIMIT 1";

        DataSource ds = datasourceService.getDataSource(query.getDatasourceId());
        try (Connection conn = ds.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.setMaxRows(1);
            try (ResultSet rs = stmt.executeQuery(metaSql)) {
                return readColumns(rs);
            }
        } catch (SQLException e) {
            log.warn("解析抽取 SQL 字段失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "读取抽取字段失败: " + e.getMessage());
        }
    }

    /** 数据源是否为 PostgreSQL（决定 SQL 校验方言） */
    private boolean isPostgresSource(Long datasourceId) {
        EtlDatasource ds = datasourceService.getById(datasourceId);
        return ds != null && ConnTypeEnum.of(ds.getConnType()) == ConnTypeEnum.POSTGRESQL;
    }

    private SourceColumnResultDTO readColumns(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        if (count <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抽取 SQL 没有返回任何列");
        }
        if (count > MAX_COLUMNS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "抽取 SQL 返回 " + count + " 列，超过上限 " + MAX_COLUMNS + "，请缩小查询范围");
        }

        // 只取一行样例：没有数据时照样能拿到元数据，类型走 JDBC 元数据
        boolean hasRow = rs.next();
        List<SourceColumnResultDTO.Column> columns = new ArrayList<>(count);
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i <= count; i++) {
            String label = StrUtil.blankToDefault(meta.getColumnLabel(i), meta.getColumnName(i));
            String name = StrUtil.isBlank(label) ? "column_" + i : label.trim();

            // MySQL 允许结果集重名（两表同名列），落库列名必须唯一
            String unique = name;
            int suffix = 2;
            while (!seen.add(unique.toLowerCase(Locale.ROOT))) {
                unique = name + "_" + suffix++;
            }
            if (!unique.equals(name)) {
                warnings.add("列名「" + name + "」重复，已重命名为「" + unique + "」");
            }

            Object sample = hasRow ? rs.getObject(i) : null;
            String type = SourceColumnTypeMapper.infer(
                    meta.getColumnType(i), meta.getPrecision(i), meta.getScale(i), sample);
            columns.add(new SourceColumnResultDTO.Column(
                    unique,
                    type,
                    meta.getColumnTypeName(i),
                    meta.isNullable(i) != ResultSetMetaData.columnNoNulls,
                    samplePreview(sample)
            ));
        }
        return new SourceColumnResultDTO(columns, warnings);
    }

    /**
     * 解析 ${xxx}：有流程上下文用流程里的变量定义，否则只替换 today / yesterday / now。
     * 仍残留的变量直接报错，避免把 "${x}" 原样当 SQL 执行。
     */
    private String resolveVariables(Long pipelineId, String sql) {
        if (!sql.contains("${")) {
            return sql;
        }
        EtlPipeline pipeline = pipelineId == null ? null : pipelineService.getById(pipelineId);
        if (pipeline == null) {
            // 新建流程还没落库：给一个空上下文，内置变量照样可用
            pipeline = new EtlPipeline();
            pipeline.setFilterMeta("");
        }
        String resolved = requestVarResolver.resolve(pipeline, sql, new StringBuilder());
        Matcher matcher = UNRESOLVED_VAR.matcher(resolved);
        if (matcher.find()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "变量 " + matcher.group() + " 无法解析，请先保存流程并定义该变量后再解析字段");
        }
        return resolved;
    }

    private void ensureSingleSelect(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<Statement> list = statements.getStatements();
            if (list.size() != 1 || !(list.getFirst() instanceof Select)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "抽取 SQL 只能是单条 SELECT 查询");
            }
        } catch (JSQLParserException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抽取 SQL 语法解析失败: " + e.getMessage());
        }
    }

    private String stripTrailingSemicolon(String sql) {
        String text = sql.trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    private String samplePreview(Object sample) {
        if (sample == null) {
            return null;
        }
        if (sample instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        String text = String.valueOf(sample);
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
