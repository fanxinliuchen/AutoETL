package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.service.EtlDatasourceService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求体变量解析器。
 *
 * <p>变量统一写成 <code>${name}</code>，取值来源有两类：
 * <ol>
 *   <li>内置变量：today / yesterday / now</li>
 *   <li>自定义变量：在过滤条件面板的「变量定义」里配置，值由一段 SQL 在目标数据源上实时计算，
 *       如 {@code DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 7) DAY)}</li>
 * </ol>
 *
 * <p>变量在流程执行时解析而非界面生成：流程多为定时任务，界面上算成固定值第二天就不对了。
 *
 * <p>遇到无法识别的 <code>${xxx}</code> 会直接抛错，避免把字面量静默发给金蝶
 * （例如已废弃的 ${today-60d} 写法）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestVarResolver {

    /** 匹配 ${任意内容}，用于识别未定义变量 */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]{1,64})}");

    /** 变量名白名单：字母/下划线开头，仅含字母数字下划线 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,31}$");

    /** 变量 SQL 的长度上限，防止有人往里塞整段脚本 */
    private static final int MAX_SQL_LENGTH = 1000;

    /** 变量 SQL 的语句超时（秒） */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    /** 变量 SQL 中禁止出现的关键字（只允许纯取值表达式） */
    private static final List<String> FORBIDDEN_SQL_FRAGMENTS = List.of(
            "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE", "SLEEP(", "BENCHMARK(",
            "INFORMATION_SCHEMA", "PERFORMANCE_SCHEMA", "MYSQL."
    );

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EtlDatasourceService datasourceService;

    /**
     * 变量定义（来自 filter_meta.variables）
     */
    @Getter
    public static class VarDef {
        private final String name;
        private final String sql;

        public VarDef(String name, String sql) {
            this.name = StrUtil.trimToEmpty(name);
            this.sql = StrUtil.trimToEmpty(sql);
        }
    }

    /**
     * 解析文本里的全部变量，返回替换后的结果。
     *
     * @param pipeline  流程配置（用到 filter_meta.variables 与 target_ds_id）
     * @param text      待替换文本（请求体 body）
     * @param logBuffer 执行日志
     */
    public String resolve(EtlPipeline pipeline, String text, StringBuilder logBuffer) {
        return resolve(pipeline, text, logBuffer, Collections.emptyMap());
    }

    /**
     * @param loopVars 循环取数注入的变量（列名 -> 值），优先级最高，会覆盖变量定义里的同名项
     */
    public String resolve(EtlPipeline pipeline,
                          String text,
                          StringBuilder logBuffer,
                          Map<String, String> loopVars) {
        if (StrUtil.isBlank(text) || !text.contains("${")) {
            return text;
        }
        Map<String, String> external = loopVars == null ? Collections.emptyMap() : loopVars;

        Map<String, String> values = builtinVariables();
        values.putAll(external);

        List<VarDef> defs = parseDefinitions(pipeline.getFilterMeta());

        // 只执行真正被引用、且没有被循环变量覆盖的变量，避免无谓查询
        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = VAR_PATTERN.matcher(text);
        while (matcher.find()) {
            used.add(matcher.group(1).trim());
        }
        List<VarDef> needed = new ArrayList<>();
        for (VarDef def : defs) {
            boolean coveredByLoop = external.keySet().stream()
                    .anyMatch(k -> k.equalsIgnoreCase(def.getName()));
            if (used.contains(def.getName()) && !coveredByLoop) {
                needed.add(def);
            }
        }
        if (!needed.isEmpty()) {
            values.putAll(executeVariables(pipeline, needed, logBuffer));
        }

        return substitute(text, values);
    }

    /**
     * 解析 filter_meta 中的变量定义
     */
    public static List<VarDef> parseDefinitions(String filterMeta) {
        if (StrUtil.isBlank(filterMeta)) {
            return Collections.emptyList();
        }
        try {
            JSONObject meta = JSONUtil.parseObj(filterMeta);
            JSONArray array = meta.getJSONArray("variables");
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            List<VarDef> defs = new ArrayList<>();
            for (Object item : array) {
                if (!(item instanceof JSONObject obj)) {
                    continue;
                }
                String name = obj.getStr("name");
                String sql = obj.getStr("sql");
                if (StrUtil.isBlank(name) || StrUtil.isBlank(sql)) {
                    continue;
                }
                defs.add(new VarDef(name, sql));
            }
            return defs;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "解析变量定义失败，请检查流程配置: " + e.getMessage());
        }
    }

    private Map<String, String> builtinVariables() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("today", LocalDate.now().toString());
        values.put("yesterday", LocalDate.now().minusDays(1).toString());
        values.put("now", LocalDateTime.now().format(DATE_TIME_FORMAT));
        return values;
    }

    private Map<String, String> executeVariables(EtlPipeline pipeline,
                                                List<VarDef> defs,
                                                StringBuilder logBuffer) {
        if (pipeline.getTargetDsId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "使用自定义变量需要先配置目标数据源：变量 SQL 在目标库上执行");
        }

        DataSource dataSource = datasourceService.getDataSource(pipeline.getTargetDsId());
        Map<String, String> result = new LinkedHashMap<>();

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            for (VarDef def : defs) {
                validateDefinition(def);
                // 变量 SQL 内允许引用内置变量（如 ${today}）
                String expression = DateVarResolver.resolve(def.getSql());
                String query = "SELECT (" + expression + ") AS v";

                try (ResultSet rs = statement.executeQuery(query)) {
                    if (!rs.next()) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                "变量 " + def.getName() + " 的 SQL 未返回任何结果");
                    }
                    Object value = rs.getObject(1);
                    if (value == null) {
                        throw new BizException(ErrorCode.PARAM_ERROR,
                                "变量 " + def.getName() + " 的计算结果为空");
                    }
                    String text = String.valueOf(value);
                    result.put(def.getName(), text);
                    if (logBuffer != null) {
                        logBuffer.append(String.format("[WebAPI] 变量 %s = %s（SQL: %s）\n",
                                def.getName(), text, StrUtil.brief(def.getSql(), 160)));
                    }
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("变量 SQL 执行失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                    "变量计算失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 单变量试算：在目标数据源上执行一次取值表达式，返回结果供界面预览。
     *
     * <p>只校验 SQL，不校验变量名——变量名可能还在编辑中，不该阻断试算。
     */
    public Map<String, Object> evaluate(Long targetDsId, String name, String sql) {
        VarDef def = new VarDef(StrUtil.isBlank(name) ? "value" : name, sql);
        validateSql(def.getName(), def.getSql());
        if (targetDsId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先选择目标数据源");
        }

        DataSource dataSource = datasourceService.getDataSource(targetDsId);
        long begin = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            String expression = DateVarResolver.resolve(def.getSql());
            try (ResultSet rs = statement.executeQuery("SELECT (" + expression + ") AS v")) {
                if (!rs.next()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "表达式未返回任何结果");
                }
                Object value = rs.getObject(1);
                if (value == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "表达式计算结果为空");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", def.getName());
                result.put("value", String.valueOf(value));
                // 用 int 而非 long：项目把 Long 统一序列化成字符串（防前端精度丢失），
                // 耗时字段需要保持数字类型
                result.put("elapsedMs", (int) (System.currentTimeMillis() - begin));
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (java.sql.SQLSyntaxErrorException e) {
            log.warn("变量试算语法错误: {}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "SQL 语法错误，请检查表达式是否完整（系统会自动包装为 SELECT (表达式)）：" + e.getMessage());
        } catch (Exception e) {
            log.warn("变量试算失败: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "试算失败: " + e.getMessage());
        }
    }

    static void validateDefinition(VarDef def) {
        if (!NAME_PATTERN.matcher(def.getName()).matches()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "变量名不合法：" + def.getName() + "（只允许字母、数字、下划线，且不以数字开头）");
        }
        validateSql(def.getName(), def.getSql());
    }

    public static void validateSql(String name, String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "变量 " + name + " 的 SQL 表达式不能为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                    "变量 " + name + " 的 SQL 过长（上限 " + MAX_SQL_LENGTH + " 字符）");
        }
        if (sql.contains(";")) {
            throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                    "变量 " + name + " 只允许单条取值表达式，不支持分号分隔多语句");
        }
        String upper = sql.toUpperCase();
        for (String fragment : FORBIDDEN_SQL_FRAGMENTS) {
            if (upper.contains(fragment)) {
                throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR,
                        "变量 " + name + " 的 SQL 包含禁止的内容: " + fragment);
            }
        }
    }

    static String substitute(String text, Map<String, String> values) {
        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        List<String> missing = new ArrayList<>();

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String value = values.get(name);
            if (value == null) {
                // 列名大小写常与引用写法不一致（如 SQL 列 startdate、写法 ${STARTDATE}），做一次忽略大小写匹配
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(name)) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            if (value == null) {
                missing.add(name);
                value = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);

        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "存在未定义的变量: " + String.join(", ", missing)
                            + "。请在过滤条件的「变量定义」中配置取值 SQL，"
                            + "或改用内置变量 ${today} / ${yesterday} / ${now}。"
                            + "注意 ${today-60d} 这类偏移写法已废弃。");
        }
        return buffer.toString();
    }
}
