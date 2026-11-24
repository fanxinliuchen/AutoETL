package com.dhp.etl.util;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取字段标识 -> 数仓列名。
 *
 * <p>金蝶的 FieldKeys 允许带点（如 {@code FSaleOrgId.FNumber} 取基础资料属性），
 * 这种标识必须原样发给金蝶，但落到临时表 / 目标表时点号不是合法列名。
 * 这里统一换算成下划线形式（{@code FSaleOrgId_FNumber}），两边规则必须一致：
 * 后端建临时表、前端字段映射与「更新目标表」都用同一套换算。
 *
 * <p>换算规则：
 * <ol>
 *   <li>非字母/数字/下划线的字符一律换成下划线</li>
 *   <li>连续下划线折叠成一个</li>
 *   <li>空串兜底为 col，数字开头的补 {@code c_} 前缀</li>
 *   <li>超过 MySQL 列名上限 64 时截断</li>
 *   <li>同批字段换算后重名时，按出现顺序追加 {@code _2}、{@code _3}</li>
 * </ol>
 */
public final class ColumnNaming {

    /** MySQL 列名上限 */
    private static final int MAX_LENGTH = 64;

    private ColumnNaming() {
    }

    /** 单个字段标识 -> 列名（不做重名处理） */
    public static String toColumn(String fieldKey) {
        if (StrUtil.isBlank(fieldKey)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(fieldKey.length());
        for (char c : fieldKey.trim().toCharArray()) {
            // 保留中文等 Unicode 字母：云表数据项与部分数仓列名本身就是中文
            boolean safe = Character.isLetterOrDigit(c) || c == '_';
            sb.append(safe ? c : '_');
        }
        String column = sb.toString().replaceAll("_+", "_");
        if (column.isEmpty()) {
            column = "col";
        }
        if (Character.isDigit(column.charAt(0))) {
            column = "c_" + column;
        }
        if (column.length() > MAX_LENGTH) {
            column = column.substring(0, MAX_LENGTH);
        }
        return column;
    }

    /** 一批字段标识 -> 「标识 -> 列名」，按顺序去重 */
    public static Map<String, String> mapping(List<String> fieldKeys) {
        Map<String, String> result = new LinkedHashMap<>();
        if (fieldKeys == null || fieldKeys.isEmpty()) {
            return result;
        }
        Map<String, Integer> used = new HashMap<>();
        for (String key : fieldKeys) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            String base = toColumn(key);
            int seq = used.merge(base.toLowerCase(), 1, Integer::sum);
            result.put(key.trim(), seq > 1 ? base + "_" + seq : base);
        }
        return result;
    }

    /** 按顺序去重后的列名列表（与入参一一对应，空标识会被跳过） */
    public static List<String> columns(List<String> fieldKeys) {
        return new ArrayList<>(mapping(fieldKeys).values());
    }
}
