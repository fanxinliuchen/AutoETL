package com.dhp.etl.core.extractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求体日期变量解析测试
 */
class DateVarResolverTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private String today() {
        return LocalDate.now().format(DATE);
    }

    private String daysAgo(int days) {
        return LocalDate.now().minusDays(days).format(DATE);
    }

    @Test
    @DisplayName("${today} / ${yesterday} 解析为日期")
    void resolveTodayAndYesterday() {
        assertEquals(today(), DateVarResolver.resolve("${today}"));
        assertEquals(daysAgo(1), DateVarResolver.resolve("${yesterday}"));
    }

    @Test
    @DisplayName("${today-60d} 解析为 60 天前")
    void resolveOffsetDays() {
        assertEquals(daysAgo(60), DateVarResolver.resolve("${today-60d}"));
        assertEquals(daysAgo(7), DateVarResolver.resolve("${today-7d}"));
        assertEquals(daysAgo(-7), DateVarResolver.resolve("${today+7d}"));
    }

    @Test
    @DisplayName("月份偏移按自然月计算")
    void resolveOffsetMonths() {
        String expected = LocalDate.now().minusMonths(1).format(DATE);
        assertEquals(expected, DateVarResolver.resolve("${today-1m}"));
    }

    @Test
    @DisplayName("完整过滤条件中的多个变量全部替换")
    void resolveInsideFilterString() {
        String filter = "FModifyDate >= '${today-60d}' AND FModifyDate < '${today}'";
        String resolved = DateVarResolver.resolve(filter);
        assertEquals("FModifyDate >= '" + daysAgo(60) + "' AND FModifyDate < '" + today() + "'", resolved);
    }

    @Test
    @DisplayName("${now} 输出到秒，便于排查")
    void resolveNow() {
        String resolved = DateVarResolver.resolve("${now}");
        assertTrue(resolved.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), resolved);
    }

    @Test
    @DisplayName("未知变量与非变量内容保持原样")
    void leaveUnknownUntouched() {
        assertEquals("${STARTDATE}", DateVarResolver.resolve("${STARTDATE}"));
        assertEquals("FDocumentStatus = 'C'", DateVarResolver.resolve("FDocumentStatus = 'C'"));
        assertEquals("${today", DateVarResolver.resolve("${today"));
    }

    @Test
    @DisplayName("空值安全")
    void nullSafe() {
        assertNull(DateVarResolver.resolve(null));
        assertEquals("", DateVarResolver.resolve(""));
    }
}
