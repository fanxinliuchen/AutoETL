package com.dhp.etl.core.guard;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PostgreSQL 源 SQL 守卫。
 *
 * <p>为什么不用 JSqlParser：PG 的方言（{@code ::} 转换、{@code ILIKE}、{@code DISTINCT ON}、
 * {@code ON CONFLICT} 等）超出它的语法树能力，硬解析会把合法 SQL 判成语法错误。
 *
 * <p>改为两道检查：
 * <ol>
 *   <li>保存时：正则黑名单 + 必须以 SELECT/WITH 开头（不连库，纯文本判断）；</li>
 *   <li>运行时：变量已替换的 SQL 走 {@code EXPLAIN (FORMAT JSON)}，
 *       由 PG 自己校验语法与权限，且用 PreparedStatement 天然拒绝多语句。</li>
 * </ol>
 */
@Slf4j
@Component
public class PostgresSqlGuard {

    /** 明确禁止的高危语句/函数 */
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\b(DROP|TRUNCATE|ALTER|CREATE)\\s+(DATABASE|SCHEMA|USER|ROLE|EXTENSION)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bGRANT\\b|\\bREVOKE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCOPY\\b[\\s\\S]*\\bFROM\\s+PROGRAM\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpg_read_file\\b|\\bpg_ls_dir\\b|\\bpg_read_binary_file\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdblink\\b|\\blo_import\\b|\\blo_export\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b", Pattern.CASE_INSENSITIVE)
    );

    /** 允许 SELECT / WITH 开头（跳过前置空白与注释） */
    private static final Pattern READ_QUERY = Pattern.compile(
            "^\\s*(?:--[^\\n]*\\n|/\\*[\\s\\S]*?\\*/\\s*)*(select|with)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 纯文本检查：保存流程时调用，不连数据库、不解析方言。
     */
    public void validateShape(String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "抽取 SQL 不能为空");
        }
        String text = sql.trim();
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(text).find()) {
                throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                        "SQL 包含禁止的高危操作: " + pattern.pattern());
            }
        }
        if (!READ_QUERY.matcher(text).find()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "PostgreSQL 抽取 SQL 只能是 SELECT / WITH 查询");
        }
        if (hasMultipleStatements(text)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "PostgreSQL 抽取 SQL 只允许单条查询语句");
        }
    }

    /**
     * 判断是否有多条语句：扫描时跳过字符串、引号标识符与注释里的分号。
     * 只允许末尾分号（{@code select ... ;}）。
     */
    static boolean hasMultipleStatements(String sql) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'') {
                    if (next == '\'') i++;
                    else inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    if (next == '"') i++;
                    else inDouble = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == ';' && !sql.substring(i + 1).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 运行时语法/权限校验：EXPLAIN 只做计划，不返回数据。
     *
     * <p>用 PreparedStatement 发送，PG 会拒绝「一条语句里塞多条命令」，
     * 不用自己切分号（也能正确跳过字符串里的分号）。
     */
    public void explain(DataSource dataSource, String sql) {
        validateShape(sql);
        String inner = stripTrailingSemicolon(sql);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("EXPLAIN (FORMAT JSON) " + inner)) {
            ps.setQueryTimeout(15);
            ps.execute();
        } catch (SQLException e) {
            log.warn("PostgreSQL SQL 预检失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                    "PostgreSQL SQL 校验失败: " + e.getMessage());
        }
    }

    private String stripTrailingSemicolon(String sql) {
        String text = sql.trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }
}
