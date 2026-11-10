package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * WebAPI 抽取分页配置（对应 t_etl_pipeline.api_pagination）
 *
 * <p>偏移量分页：每轮把「偏移字段」按 初始值 + 第几页 × 增长间隔 写回请求体，
 * 同时把「每页字段」写成每页条数，直到某页返回条数小于结束阈值或达到取数上限。
 *
 * <p>默认值直接适配金蝶 ExecuteBillQuery / BillQuery：
 * {@code {"data":{"StartRow":0,"Limit":2000}}}
 */
@Slf4j
@Data
public class PaginationConfig {

    /** 是否启用分页 */
    private boolean enabled = false;

    /** 偏移量在请求体中的 JSON 路径，如 data.StartRow */
    private String offsetPath = "data.StartRow";

    /** 每页条数在请求体中的 JSON 路径，如 data.Limit */
    private String limitPath = "data.Limit";

    /** 偏移量初始值 */
    private int start = 0;

    /** 偏移量增长间隔（<=0 时取每页条数） */
    private int step = 0;

    /** 每页条数 */
    private int pageSize = 2000;

    /** 最多取数次数（分页轮次上限） */
    private int maxPages = 200;

    /** 结束条件：本页条数 < 该值即认为取完（<=0 时取每页条数） */
    private int endWhenLessThan = 0;

    /** 调用频率限制：两次请求之间的间隔毫秒 */
    private long intervalMs = 0;

    /** 是否显式配置过分页路径（显式配置时不做自动适配） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean pathCustomized = false;

    /**
     * 解析配置；为空或非法配置时返回 null（不退化为分页，保持单次请求行为）
     */
    public static PaginationConfig parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            PaginationConfig config = new PaginationConfig();
            config.setEnabled(obj.getBool("enabled", false));
            if (!config.isEnabled()) {
                return null;
            }
            config.setPathCustomized(obj.containsKey("offsetPath") || obj.containsKey("limitPath"));
            config.setOffsetPath(StrUtil.blankToDefault(obj.getStr("offsetPath"), "data.StartRow"));
            config.setLimitPath(StrUtil.blankToDefault(obj.getStr("limitPath"), "data.Limit"));
            config.setStart(clamp(obj.getInt("start", 0), 0, 100_000_000));
            config.setStep(clamp(obj.getInt("step", 0), 0, 1_000_000));
            config.setPageSize(clamp(obj.getInt("pageSize", 2000), 1, 10000));
            config.setMaxPages(clamp(obj.getInt("maxPages", 200), 1, 10000));
            config.setEndWhenLessThan(clamp(obj.getInt("endWhenLessThan", 0), 0, 10000));
            config.setIntervalMs(clampLong(obj.getLong("intervalMs", 0L), 0L, 60_000L));
            return config;
        } catch (Exception e) {
            log.warn("分页配置解析失败，将按单次请求执行: {}", e.getMessage());
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 实际使用的增长间隔 */
    public int effectiveStep() {
        return step > 0 ? step : pageSize;
    }

    /** 实际使用的结束阈值 */
    public int effectiveEnd() {
        return endWhenLessThan > 0 ? endWhenLessThan : pageSize;
    }

    /** 第 pageIndex 页（从 0 开始）的偏移量 */
    public int offsetOf(int pageIndex) {
        return start + pageIndex * effectiveStep();
    }

    /**
     * 按点号路径把值写入请求体，路径不存在时自动创建中间节点
     */
    public static void setPath(JSONObject root, String path, Object value) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        String[] segments = path.split("\\.");
        JSONObject cursor = root;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            int bracket = segment.indexOf('[');
            if (bracket > 0 && segment.endsWith("]")) {
                // 支持 parameters[0].xxx 这类数组路径
                String key = segment.substring(0, bracket);
                int index;
                try {
                    index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "分页字段路径不合法: " + path);
                }
                cn.hutool.json.JSONArray array = cursor.getJSONArray(key);
                if (array == null) {
                    array = new cn.hutool.json.JSONArray();
                    cursor.set(key, array);
                }
                while (array.size() <= index) {
                    array.add(new JSONObject());
                }
                Object element = array.get(index);
                if (!(element instanceof JSONObject)) {
                    JSONObject created = new JSONObject();
                    array.set(index, created);
                    cursor = created;
                } else {
                    cursor = (JSONObject) element;
                }
                continue;
            }
            Object next = cursor.get(segment);
            if (!(next instanceof JSONObject)) {
                JSONObject created = new JSONObject();
                cursor.set(segment, created);
                cursor = created;
            } else {
                cursor = (JSONObject) next;
            }
        }
        cursor.set(segments[segments.length - 1], value);
    }

    /**
     * 按请求体形态自动适配分页路径。
     *
     * <p>金蝶有两种取数报文：
     * <ul>
     *   <li>标准查询：{@code {"data":{"StartRow":0,"Limit":100}}} 如 ExecuteBillQuery</li>
     *   <li>报表方案查询：{@code {"parameters":[{"StartRow":0,"Limit":100}]}} 如带 QuicklyCondition 的报表</li>
     * </ul>
     * 未显式配置路径时按报文自动切换，避免两处配置打架。
     */
    public void adaptToRequestBody(JSONObject body) {
        if (body == null || pathCustomized) {
            return;
        }
        if (body.containsKey("parameters")) {
            setOffsetPath("parameters[0].StartRow");
            setLimitPath("parameters[0].Limit");
        } else {
            setOffsetPath("data.StartRow");
            setLimitPath("data.Limit");
        }
    }

    /** 配置合法性校验（供保存时提示） */
    public void validate() {
        if (pageSize < 1 || pageSize > 10000) {
            throw new BizException(ErrorCode.PARAM_ERROR, "每页条数需在 1 ~ 10000 之间");
        }
        if (maxPages < 1) {
            throw new BizException(ErrorCode.PARAM_ERROR, "最多取数次数必须大于 0");
        }
    }
}
