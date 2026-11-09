package com.dhp.etl.core.guard;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全守卫 - 基于 JSqlParser AST 语法树解析与白名单控制 (方案修正 P0-1)
 */
@Slf4j
@Component
public class SqlSafetyGuard {

    private static final Set<Class<?>> ALLOWED_STATEMENTS = Set.of(
            Select.class,
            Insert.class,
            Update.class,
            Delete.class,
            Truncate.class,
            CreateTable.class
    );

    /**
     * 严禁执行的高危系统关键字
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("\\bDROP\\s+(DATABASE|INDEX)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bGRANT\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bREVOKE\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bLOAD\\s+DATA\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bCALL\\s+sys\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSHUTDOWN\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 动态变量占位符 ${today} / ${stage_table} 等。JSqlParser 不认识 ${...}，
     * 解析前先替换为合法标识符，否则任何带变量的脚本都会被误判为语法错误。
     */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{[A-Za-z0-9_]+}");

    /**
     * 校验 SQL 脚本安全性
     */
    public void validate(String sqlScript) {
        if (StrUtil.isBlank(sqlScript)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "SQL脚本不能为空");
        }

        // 1. 正则黑名单排查（对原始脚本做，避免变量替换掩盖关键字）
        for (Pattern pattern : FORBIDDEN_PATTERNS) {
            if (pattern.matcher(sqlScript).find()) {
                throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                        "SQL 包含禁止的高危操作: " + pattern.pattern());
            }
        }

        // 2. JSqlParser AST 白名单校验（变量先替换为占位标识符再解析）
        String normalized = VAR_PATTERN.matcher(sqlScript).replaceAll("_var_");
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(normalized);
            for (Statement stmt : statements.getStatements()) {
                boolean allowed = ALLOWED_STATEMENTS.stream().anyMatch(clazz -> clazz.isInstance(stmt));
                if (!allowed) {
                    throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                            "不允许的 SQL 语句类型: " + stmt.getClass().getSimpleName());
                }
            }
        } catch (JSQLParserException e) {
            log.error("SQL 解析失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR, "SQL 语法解析错误: " + e.getMessage());
        }
    }
}
