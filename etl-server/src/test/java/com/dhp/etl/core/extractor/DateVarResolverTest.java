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
    @DisplayName("已废弃的偏移写法不再解析，交由变量解析器报错")
    void offsetSyntaxDeprecated() {
        assertEquals("${today-60d}", DateVarResolver.resolve("${today-60d}"));
        assertEquals("${today+7d}", DateVarResolver.resolve("${today+7d}"));
        assertEquals("${acctWeekStart-1w}", DateVarResolver.resolve("${acctWeekStart-1w}"));
    }

    @Test
    @DisplayName("完整过滤条件中的内置变量全部替换")
    void resolveInsideFilterString() {
        String filter = "FModifyDate >= '${yesterday}' AND FModifyDate < '${today}'";
        String resolved = DateVarResolver.resolve(filter);
        assertEquals("FModifyDate >= '" + daysAgo(1) + "' AND FModifyDate < '" + today() + "'", resolved);
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
