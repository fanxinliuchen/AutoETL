package com.dhp.etl.core.extractor;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 分页配置解析与请求体改写测试
 */
class PaginationConfigTest {

    @Test
    @DisplayName("未启用或配置为空时返回 null，保持单次请求")
    void disabledReturnsNull() {
        assertNull(PaginationConfig.parse(null));
        assertNull(PaginationConfig.parse(""));
        assertNull(PaginationConfig.parse("{\"enabled\":false}"));
    }

    @Test
    @DisplayName("非法 JSON 不抛异常，退化为单次请求")
    void invalidJsonReturnsNull() {
        assertNull(PaginationConfig.parse("{不是JSON"));
    }

    @Test
    @DisplayName("默认值适配金蝶 ExecuteBillQuery")
    void parseDefaults() {
        PaginationConfig config = PaginationConfig.parse("{\"enabled\":true}");
        assertNotNull(config);
        assertEquals("data.StartRow", config.getOffsetPath());
        assertEquals("data.Limit", config.getLimitPath());
        assertEquals(2000, config.getPageSize());
        assertEquals(200, config.getMaxPages());
        assertEquals(0, config.getStart());
        // step 未配置时用每页条数
        assertEquals(2000, config.effectiveStep());
        assertEquals(2000, config.effectiveEnd());
    }

    @Test
    @DisplayName("越界参数被收敛到安全范围")
    void clampOutOfRange() {
        PaginationConfig config = PaginationConfig.parse(
                "{\"enabled\":true,\"pageSize\":999999,\"maxPages\":0,\"intervalMs\":999999,\"start\":-5}");
        assertNotNull(config);
        assertEquals(10000, config.getPageSize());
        assertEquals(1, config.getMaxPages());
        assertEquals(60000L, config.getIntervalMs());
        assertEquals(0, config.getStart());
    }

    @Test
    @DisplayName("偏移量按 初始值 + 页码 × 间隔 计算")
    void offsetCalculation() {
        PaginationConfig config = PaginationConfig.parse(
                "{\"enabled\":true,\"pageSize\":500,\"start\":100,\"step\":500}");
        assertNotNull(config);
        assertEquals(100, config.offsetOf(0));
        assertEquals(600, config.offsetOf(1));
        assertEquals(1100, config.offsetOf(2));
    }

    @Test
    @DisplayName("自定义结束阈值优先于每页条数")
    void customEndThreshold() {
        PaginationConfig config = PaginationConfig.parse(
                "{\"enabled\":true,\"pageSize\":9999,\"endWhenLessThan\":9000}");
        assertNotNull(config);
        assertEquals(9000, config.effectiveEnd());
    }

    @Test
    @DisplayName("按点号路径改写请求体，中间节点自动创建且不影响其它字段")
    void setPathOnBody() {
        JSONObject body = JSONUtil.parseObj("{\"data\":{\"FormId\":\"PUR_PurchaseOrder\"}}");

        PaginationConfig.setPath(body, "data.StartRow", 4000);
        PaginationConfig.setPath(body, "data.Limit", 2000);

        assertEquals(4000, body.getByPath("data.StartRow", Integer.class));
        assertEquals(2000, body.getByPath("data.Limit", Integer.class));
        assertEquals("PUR_PurchaseOrder", body.getByPath("data.FormId", String.class));
    }

    @Test
    @DisplayName("路径不存在时逐层创建")
    void setPathCreatesNested() {
        JSONObject body = JSONUtil.parseObj("{}");
        PaginationConfig.setPath(body, "page.offset.value", 20);
        assertEquals(20, body.getByPath("page.offset.value", Integer.class));
    }

    // ===== 金蝶两种报文的路径适配 =====

    @Test
    @DisplayName("标准报文：沿用 data.StartRow / data.Limit")
    void adaptStandardBody() {
        PaginationConfig config = PaginationConfig.parse("{\"enabled\":true,\"pageSize\":100}");
        JSONObject body = JSONUtil.parseObj("{\"data\":{\"FormId\":\"PUR_PurchaseOrder\",\"StartRow\":0,\"Limit\":100}}");
        config.adaptToRequestBody(body);
        assertEquals("data.StartRow", config.getOffsetPath());
        assertEquals("data.Limit", config.getLimitPath());

        PaginationConfig.setPath(body, config.getOffsetPath(), 300);
        assertEquals(300, body.getJSONObject("data").getInt("StartRow"));
    }

    @Test
    @DisplayName("报表报文：自动切到 parameters[0].StartRow / Limit")
    void adaptReportBody() {
        PaginationConfig config = PaginationConfig.parse("{\"enabled\":true,\"pageSize\":100}");
        JSONObject body = JSONUtil.parseObj(
                "{\"parameters\":[{\"FORMID\":\"STK_StockSummaryRpt\",\"StartRow\":0,\"Limit\":100}]}");
        config.adaptToRequestBody(body);

        assertEquals("parameters[0].StartRow", config.getOffsetPath());
        assertEquals("parameters[0].Limit", config.getLimitPath());

        PaginationConfig.setPath(body, config.getOffsetPath(), 500);
        PaginationConfig.setPath(body, config.getLimitPath(), 200);
        JSONObject first = body.getJSONArray("parameters").getJSONObject(0);
        assertEquals(500, first.getInt("StartRow"));
        assertEquals(200, first.getInt("Limit"));
        // 原有字段不能被破坏
        assertEquals("STK_StockSummaryRpt", first.getStr("FORMID"));
    }

    @Test
    @DisplayName("路径与报文体结构冲突时以结构为准（避免凭空造出 data 节点）")
    void overridePathWhenStructureMismatch() {
        PaginationConfig config = PaginationConfig.parse(
                "{\"enabled\":true,\"offsetPath\":\"data.StartRow\",\"limitPath\":\"data.Limit\"}");
        JSONObject body = JSONUtil.parseObj("{\"parameters\":[{\"StartRow\":0}]}");
        config.adaptToRequestBody(body);
        assertEquals("parameters[0].StartRow", config.getOffsetPath());
        assertEquals("parameters[0].Limit", config.getLimitPath());
    }

    @Test
    @DisplayName("与报文体结构一致的显式路径保持不变")
    void keepCustomPath() {
        PaginationConfig config = PaginationConfig.parse(
                "{\"enabled\":true,\"offsetPath\":\"parameters[0].StartRow\",\"limitPath\":\"parameters[0].Limit\"}");
        JSONObject body = JSONUtil.parseObj("{\"parameters\":[{\"StartRow\":0}]}");
        config.adaptToRequestBody(body);
        assertEquals("parameters[0].StartRow", config.getOffsetPath());
    }

    @Test
    @DisplayName("parameters 数组不存在时按需创建")
    void createArrayNodeIfMissing() {
        PaginationConfig config = PaginationConfig.parse("{\"enabled\":true}");
        JSONObject body = JSONUtil.parseObj("{\"parameters\":[{}]}");
        config.adaptToRequestBody(body);
        PaginationConfig.setPath(body, config.getOffsetPath(), 42);
        assertEquals(42, body.getJSONArray("parameters").getJSONObject(0).getInt("StartRow"));
    }
}
