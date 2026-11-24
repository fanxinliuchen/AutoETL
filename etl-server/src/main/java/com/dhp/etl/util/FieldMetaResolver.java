package com.dhp.etl.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抽取字段元数据解析器。
 *
 * <p>把前端 field_meta JSON 转成「列名 -> 安全建表类型」映射。类型必须走白名单：
 * 临时表 DDL 是字符串拼接，直接采纳用户输入会形成 SQL 注入。
 */
@Slf4j
public final class FieldMetaResolver {

    private static final Pattern VARCHAR_PATTERN = Pattern.compile("^VARCHAR\\((\\d{1,4})\\)$");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^DECIMAL\\((\\d{1,2}),(\\d{1,2})\\)$");
    private static final Pattern CHAR_PATTERN = Pattern.compile("^CHAR\\((\\d{1,4})\\)$");

    private FieldMetaResolver() {
    }

    /**
     * 解析 field_meta，返回「列名 -> 归一化类型」；未填类型的列按 VARCHAR(255) 处理。
     * 解析失败时不抛异常，整体退回 VARCHAR(255)，避免因元数据损坏阻断抽取。
     */
    public static Map<String, String> parseColumnTypes(String fieldMetaJson) {
        Map<String, String> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(fieldMetaJson)) {
            return result;
        }
        try {
            Map<String, JSONObject> byKey = enabledFieldMap(fieldMetaJson);
            List<String> keys = new ArrayList<>(byKey.keySet());
            Map<String, String> columnByKey = columnMapping(fieldMetaJson, keys);
            for (String key : keys) {
                result.put(columnByKey.getOrDefault(key, ColumnNaming.toColumn(key)),
                        normalizeType(byKey.get(key).getStr("type")));
            }
        } catch (Exception e) {
            log.warn("field_meta 解析失败，临时表将全部按 VARCHAR(255) 建表: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 字段清单里启用的字段标识（顺序即列序）。
     *
     * <p>云表/自定义报文没有 FieldKeys，只能以这份清单为准。
     */
    public static List<String> enabledKeys(String fieldMetaJson) {
        try {
            return new ArrayList<>(enabledFieldMap(fieldMetaJson).keySet());
        } catch (Exception e) {
            log.warn("field_meta 解析失败，字段清单按空处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 启用字段：key -> 字段对象（保持配置顺序） */
    private static Map<String, JSONObject> enabledFieldMap(String fieldMetaJson) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        if (StrUtil.isBlank(fieldMetaJson)) {
            return map;
        }
        JSONObject root = JSONUtil.parseObj(fieldMetaJson);
        JSONArray fields = root.getJSONArray("fields");
        if (fields == null) {
            return map;
        }
        for (int i = 0; i < fields.size(); i++) {
            JSONObject field = fields.getJSONObject(i);
            if (field == null) {
                continue;
            }
            String key = StrUtil.trimToEmpty(field.getStr("k"));
            if (StrUtil.isBlank(key)) {
                continue;
            }
            if (Boolean.FALSE.equals(field.getBool("enabled", Boolean.TRUE))) {
                continue;
            }
            map.putIfAbsent(key, field);
        }
        return map;
    }

    /** 列名白名单：允许中文/字母开头，只含中文/字母/数字/下划线（MySQL 8 + utf8mb4 原生支持中文列名） */
    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_]{0,63}$");

    /**
     * 字段标识 -> 落库列名。
     *
     * <p>优先级：field_meta 里手工指定的 {@code column} > 按标识换算（{@link ColumnNaming}）。
     * 云表的数据项名基本是中文，手工列名是必需的逃生门。
     */
    public static Map<String, String> columnMapping(String fieldMetaJson, List<String> fieldKeys) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(fieldMetaJson)) {
            try {
                for (Map.Entry<String, JSONObject> entry : enabledFieldMap(fieldMetaJson).entrySet()) {
                    String column = StrUtil.trimToEmpty(entry.getValue().getStr("column"));
                    if (StrUtil.isNotBlank(column)) {
                        if (!COLUMN_PATTERN.matcher(column).matches()) {
                            throw new BizException(ErrorCode.PARAM_ERROR,
                                    "字段「" + entry.getKey() + "」的列名不合法：" + column
                                            + "（只允许中文/字母/数字/下划线，且不以数字开头）");
                        }
                        overrides.put(entry.getKey(), column);
                    }
                }
            } catch (BizException be) {
                throw be;
            } catch (Exception e) {
                log.warn("field_meta 列名解析失败，按字段标识换算: {}", e.getMessage());
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (fieldKeys == null) {
            return result;
        }
        Map<String, String> base = ColumnNaming.mapping(fieldKeys);
        for (String key : fieldKeys) {
            result.put(key, overrides.getOrDefault(key, base.getOrDefault(key, ColumnNaming.toColumn(key))));
        }
        return result;
    }

    /**
     * 类型归一化（白名单）。未填类型按 VARCHAR(255)，非法或越界降级为 TEXT（保数据不截断）。
     */
    public static String normalizeType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "VARCHAR(255)";
        }
        String type = raw.trim().toUpperCase().replaceAll("\\s+", "");
        switch (type) {
            case "TEXT":
            case "BIGINT":
            case "INT":
            case "TINYINT":
            case "SMALLINT":
            case "DOUBLE":
            case "BOOLEAN":
            case "DATE":
            case "DATETIME":
            case "TIMESTAMP":
                return type;
            default:
                break;
        }
        Matcher varchar = VARCHAR_PATTERN.matcher(type);
        if (varchar.matches()) {
            int length = Integer.parseInt(varchar.group(1));
            return (length >= 1 && length <= 2000) ? "VARCHAR(" + length + ")" : "TEXT";
        }
        Matcher charType = CHAR_PATTERN.matcher(type);
        if (charType.matches()) {
            int length = Integer.parseInt(charType.group(1));
            return (length >= 1 && length <= 255) ? "CHAR(" + length + ")" : "VARCHAR(255)";
        }
        Matcher decimal = DECIMAL_PATTERN.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            boolean valid = precision >= 1 && precision <= 30 && scale <= 10 && scale <= precision;
            return valid ? "DECIMAL(" + precision + "," + scale + ")" : "TEXT";
        }
        return "TEXT";
    }

    /**
     * 归一化类型 -> information_schema.DATA_TYPE，用于比对待建表与现有表结构。
     */
    public static String baseType(String normalizedType) {
        if (StrUtil.isBlank(normalizedType)) {
            return "text";
        }
        int idx = normalizedType.indexOf('(');
        String base = idx > 0 ? normalizedType.substring(0, idx) : normalizedType;
        return base.toLowerCase();
    }
}
