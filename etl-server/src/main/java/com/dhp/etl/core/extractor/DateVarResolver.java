package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求体里的日期变量解析器。
 *
 * <p>支持写法：
 * <ul>
 *   <li>自然日：${today}、${yesterday}、${now}</li>
 *   <li>偏移量：${today-60d}、${today+7d}、${yesterday-1m}、${today-1y}、${today-2w}</li>
 *   <li>会计周：${acctWeekStart}、${acctWeekEnd} 及其偏移 ${acctWeekStart-1w}</li>
 *   <li>上个会计周简写：${lastAcctWeekStart}、${lastAcctWeekEnd}</li>
 * </ul>
 *
 * <p>会计周 = 从「会计周起始日」（默认周五）到其前一天的 7 天周期。
 * 起始日可按账套习惯配置（周五/周一/周日等），由 filter_meta.weekStart 指定。
 *
 * <p>之所以放在后端解析而不是界面生成：流程通常是定时跑的，
 * 若在界面上算成固定日期，第二天跑就还是旧区间；会计周还会随周滚动。
 */
public final class DateVarResolver {

    /** 变量名 + 若干段偏移，如 ${acctWeekStart-1w}、${today-60d} */
    private static final Pattern VAR_PATTERN = Pattern.compile(
            "\\$\\{(today|yesterday|now|acctWeekStart|acctWeekEnd|lastAcctWeekStart|lastAcctWeekEnd)"
                    + "((?:[+-]\\d{1,4}[dmyw])*)}",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OFFSET_PATTERN = Pattern.compile(
            "([+-])(\\d{1,4})([dmyw])", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认会计周起始日：周五（周五 ~ 次周四为一个会计周） */
    public static final DayOfWeek DEFAULT_WEEK_START = DayOfWeek.FRIDAY;

    private DateVarResolver() {
    }

    public static String resolve(String text) {
        return resolve(text, DEFAULT_WEEK_START);
    }

    /**
     * @param weekStartName 会计周起始日，取 DayOfWeek 名称（MONDAY / FRIDAY …），非法值回退默认
     */
    public static String resolve(String text, String weekStartName) {
        return resolve(text, parseWeekStart(weekStartName));
    }

    public static String resolve(String text, DayOfWeek weekStart) {
        if (StrUtil.isBlank(text) || !text.contains("${")) {
            return text;
        }
        DayOfWeek start = weekStart == null ? DEFAULT_WEEK_START : weekStart;
        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = evaluate(matcher, start);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public static DayOfWeek parseWeekStart(String name) {
        if (StrUtil.isBlank(name)) {
            return DEFAULT_WEEK_START;
        }
        try {
            return DayOfWeek.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT_WEEK_START;
        }
    }

    /**
     * 计算某天所在会计周的起始日。
     * <p>例：起始日为周五时，2026-11-09（周一）所在会计周从 2026-11-06（周五）开始。
     */
    public static LocalDate accountingWeekStart(LocalDate date, DayOfWeek weekStart) {
        DayOfWeek start = weekStart == null ? DEFAULT_WEEK_START : weekStart;
        int diff = (date.getDayOfWeek().getValue() - start.getValue() + 7) % 7;
        return date.minusDays(diff);
    }

    private static String evaluate(Matcher matcher, DayOfWeek weekStart) {
        String base = matcher.group(1).toLowerCase();
        String offsets = matcher.group(2);

        // ${now} 保留到秒，便于调试；带偏移的统一按日期输出
        if ("now".equals(base) && StrUtil.isBlank(offsets)) {
            return LocalDateTime.now().format(DATE_TIME_FORMAT);
        }

        // 「上个会计周」= 会计周起止各往前推一周
        boolean lastAccountingWeek =
                "lastacctweekstart".equals(base) || "lastacctweekend".equals(base);
        if ("lastacctweekstart".equals(base)) {
            base = "acctweekstart";
        } else if ("lastacctweekend".equals(base)) {
            base = "acctweekend";
        }

        LocalDate today = LocalDate.now();
        LocalDate date;
        switch (base) {
            case "yesterday" -> date = today.minusDays(1);
            case "acctweekstart" -> date = accountingWeekStart(today, weekStart);
            case "acctweekend" -> date = accountingWeekStart(today, weekStart).plusDays(6);
            default -> date = today;
        }
        if (lastAccountingWeek) {
            date = date.minusWeeks(1);
        }

        Matcher offsetMatcher = OFFSET_PATTERN.matcher(offsets);
        while (offsetMatcher.find()) {
            int amount = Integer.parseInt(offsetMatcher.group(2));
            boolean plus = "+".equals(offsetMatcher.group(1));
            switch (offsetMatcher.group(3).toLowerCase()) {
                case "d" -> date = plus ? date.plusDays(amount) : date.minusDays(amount);
                case "w" -> date = plus ? date.plusWeeks(amount) : date.minusWeeks(amount);
                case "m" -> date = plus ? date.plusMonths(amount) : date.minusMonths(amount);
                case "y" -> date = plus ? date.plusYears(amount) : date.minusYears(amount);
                default -> {
                }
            }
        }
        return date.toString();
    }
}
