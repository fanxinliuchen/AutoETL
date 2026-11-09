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
}
