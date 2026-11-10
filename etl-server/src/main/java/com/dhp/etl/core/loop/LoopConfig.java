package com.dhp.etl.core.loop;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.Data;

/**
 * 循环取数配置（对应 filter_meta.loop）。
 *
 * <p>一次运行展开为 N 轮：循环 SQL 在目标数据源上执行，返回的每一行就是一轮的完整参数集，
 * 列名即变量名。需要「工厂 × 日期」这类组合时，直接在 SQL 里 CROSS JOIN 出行，系统只负责逐行执行。
 */
@Data
public class LoopConfig {

    /** 是否启用 */
    private boolean enabled = false;

    /** 循环数据集 SQL（多行多列，列名即变量名） */
    private String sql;

    /** 最多循环次数，超出直接报错而不是静默截断 */
    private int maxIterations = 200;

    /** 每轮之间的间隔毫秒 */
    private long intervalMs = 0;

    /**
     * 从 filter_meta 解析；未启用返回 null
     */
    public static LoopConfig parse(String filterMeta) {
        if (StrUtil.isBlank(filterMeta)) {
            return null;
        }
        JSONObject meta;
        try {
            meta = JSONUtil.parseObj(filterMeta);
        } catch (Exception e) {
            return null;
        }
        JSONObject loop = meta.getJSONObject("loop");
        if (loop == null || !loop.getBool("enabled", false)) {
            return null;
        }
        String sql = StrUtil.trimToEmpty(loop.getStr("sql"));
        if (StrUtil.isBlank(sql)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "已开启循环取数，但循环数据集 SQL 为空：请填写返回多行参数的查询语句");
        }
        LoopConfig config = new LoopConfig();
        config.setEnabled(true);
        config.setSql(sql);
        config.setMaxIterations(clamp(loop.getInt("maxIterations", 200), 1, 10000));
        config.setIntervalMs(clampLong(loop.getLong("intervalMs", 0L), 0L, 60_000L));
        return config;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
