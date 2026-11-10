package com.dhp.etl.core.loop;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 循环取数配置解析
 */
class LoopConfigTest {

    @Test
    @DisplayName("未配置或未启用返回 null")
    void disabledReturnsNull() {
        assertNull(LoopConfig.parse(null));
        assertNull(LoopConfig.parse(""));
        assertNull(LoopConfig.parse("{\"variables\":[]}"));
        assertNull(LoopConfig.parse("{\"loop\":{\"enabled\":false,\"sql\":\"SELECT 1\"}}"));
        assertNull(LoopConfig.parse("不是JSON"));
    }

    @Test
    @DisplayName("启用但 SQL 为空直接报错，避免静默只跑一轮")
    void enabledWithoutSqlFails() {
        BizException e = assertThrows(BizException.class,
                () -> LoopConfig.parse("{\"loop\":{\"enabled\":true,\"sql\":\"\"}}"));
        assertTrue(e.getMessage().contains("循环数据集 SQL 为空"), e.getMessage());
    }

    @Test
    @DisplayName("正常解析循环配置")
    void parseOk() {
        LoopConfig config = LoopConfig.parse(
                "{\"loop\":{\"enabled\":true,\"sql\":\"SELECT startdate FROM weeks\","
                        + "\"maxIterations\":50,\"intervalMs\":1000}}");
        assertNotNull(config);
        assertTrue(config.isEnabled());
        assertEquals("SELECT startdate FROM weeks", config.getSql());
        assertEquals(50, config.getMaxIterations());
        assertEquals(1000L, config.getIntervalMs());
    }

    @Test
    @DisplayName("上限与间隔越界时收敛到安全区间")
    void clampValues() {
        LoopConfig tooBig = LoopConfig.parse(
                "{\"loop\":{\"enabled\":true,\"sql\":\"SELECT 1\",\"maxIterations\":999999}}");
        assertEquals(10000, tooBig.getMaxIterations());

        LoopConfig zero = LoopConfig.parse(
                "{\"loop\":{\"enabled\":true,\"sql\":\"SELECT 1\",\"maxIterations\":0}}");
        assertEquals(1, zero.getMaxIterations());

        LoopConfig negativeInterval = LoopConfig.parse(
                "{\"loop\":{\"enabled\":true,\"sql\":\"SELECT 1\",\"intervalMs\":-5}}");
        assertEquals(0L, negativeInterval.getIntervalMs());
    }
}
