package com.dhp.etl.core.extractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
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

    // ===== 会计周 =====

    @Test
    @DisplayName("会计周起始日：周五起始时，周一属于上周五开始的那一周")
    void accountingWeekStartFixedDates() {
        // 2026-11-09 是周一，所在会计周从 2026-11-06（周五）开始
        assertEquals(LocalDate.of(2026, 11, 6),
                DateVarResolver.accountingWeekStart(LocalDate.of(2026, 11, 9), DayOfWeek.FRIDAY));
        // 周五当天即本周起始
        assertEquals(LocalDate.of(2026, 11, 6),
                DateVarResolver.accountingWeekStart(LocalDate.of(2026, 11, 6), DayOfWeek.FRIDAY));
        // 周四属于上一周（10-30 ~ 11-05）
        assertEquals(LocalDate.of(2026, 10, 30),
                DateVarResolver.accountingWeekStart(LocalDate.of(2026, 11, 5), DayOfWeek.FRIDAY));
        // 起始日可切换为周一
        assertEquals(LocalDate.of(2026, 11, 9),
                DateVarResolver.accountingWeekStart(LocalDate.of(2026, 11, 9), DayOfWeek.MONDAY));
    }

    @Test
    @DisplayName("上周会计周与用户 SQL 的 DATE_SUB 公式等价")
    void lastAccountingWeekMatchesUserSql() {
        // 用户 SQL（起始日周五）：
        //   startdate = d - ((WEEKDAY(d)+3)%7 + 7)
        //   enddate   = d - ((WEEKDAY(d)+3)%7 + 1)
        // 等价于「本周会计周起始 - 7 天」与「本周会计周起始 - 1 天」
        LocalDate probe = LocalDate.of(2026, 10, 23);
        LocalDate start = DateVarResolver.accountingWeekStart(probe, DayOfWeek.FRIDAY).minusWeeks(1);
        LocalDate end = start.plusDays(6);
        assertEquals(LocalDate.of(2026, 10, 16), start);
        assertEquals(LocalDate.of(2026, 10, 22), end);
    }

    @Test
    @DisplayName("${acctWeekStart} / ${acctWeekEnd} 输出本周会计周")
    void resolveCurrentAccountingWeek() {
        LocalDate start = DateVarResolver.accountingWeekStart(LocalDate.now(), DayOfWeek.FRIDAY);
        assertEquals(start.toString(), DateVarResolver.resolve("${acctWeekStart}"));
        assertEquals(start.plusDays(6).toString(), DateVarResolver.resolve("${acctWeekEnd}"));
    }

    @Test
    @DisplayName("上个会计周支持简写与 -1w 两种写法")
    void resolveLastAccountingWeek() {
        LocalDate start = DateVarResolver.accountingWeekStart(LocalDate.now(), DayOfWeek.FRIDAY);
        LocalDate lastStart = start.minusWeeks(1);
        LocalDate lastEnd = lastStart.plusDays(6);
        assertEquals(lastStart.toString(), DateVarResolver.resolve("${lastAcctWeekStart}"));
        assertEquals(lastEnd.toString(), DateVarResolver.resolve("${lastAcctWeekEnd}"));
        assertEquals(lastStart.toString(), DateVarResolver.resolve("${acctWeekStart-1w}"));
        assertEquals(lastEnd.toString(), DateVarResolver.resolve("${acctWeekEnd-1w}"));
    }

    @Test
    @DisplayName("会计周区间右边界不含本周，可直接用于 >= start AND < end")
    void resolveAccountingWeekRange() {
        String filter = "FModifyDate >= '${lastAcctWeekStart}' AND FModifyDate < '${acctWeekStart}'";
        LocalDate start = DateVarResolver.accountingWeekStart(LocalDate.now(), DayOfWeek.FRIDAY);
        String expected = "FModifyDate >= '" + start.minusWeeks(1) + "' AND FModifyDate < '" + start + "'";
        assertEquals(expected, DateVarResolver.resolve(filter));
    }

    @Test
    @DisplayName("会计周起始日可配置，非法值回退周五")
    void resolveWithCustomWeekStart() {
        LocalDate mondayStart = DateVarResolver.accountingWeekStart(LocalDate.now(), DayOfWeek.MONDAY);
        assertEquals(mondayStart.toString(), DateVarResolver.resolve("${acctWeekStart}", "MONDAY"));
        assertEquals(mondayStart.toString(), DateVarResolver.resolve("${acctWeekStart}", " monday "));
        assertEquals(DateVarResolver.DEFAULT_WEEK_START, DateVarResolver.parseWeekStart("NOT_A_DAY"));
        assertEquals(DateVarResolver.DEFAULT_WEEK_START, DateVarResolver.parseWeekStart(null));
    }

    @Test
    @DisplayName("周偏移单位 w 与多段偏移")
    void resolveWeekOffset() {
        assertEquals(daysAgo(7), DateVarResolver.resolve("${today-1w}"));
        assertEquals(daysAgo(14), DateVarResolver.resolve("${today-2w}"));
        // 多段偏移按顺序累加
        assertEquals(daysAgo(67), DateVarResolver.resolve("${today-7d-60d}"));
    }
}
