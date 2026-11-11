package com.dhp.etl.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
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
            JSONObject root = JSONUtil.parseObj(fieldMetaJson);
            JSONArray fields = root.getJSONArray("fields");
            if (fields == null) {
                return result;
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
                result.put(key, normalizeType(field.getStr("type")));
            }
        } catch (Exception e) {
            log.warn("field_meta 解析失败，临时表将全部按 VARCHAR(255) 建表: {}", e.getMessage());
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
