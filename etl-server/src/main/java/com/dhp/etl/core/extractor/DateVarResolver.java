package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求体里的日期变量解析器。
 *
 * <p>支持写法：${today}、${yesterday}、${now}，以及带偏移的
 * ${today-60d}、${today+7d}、${yesterday-1m}、${today-1y}。
 * 单位：d=天、m=月、y=年；解析结果形如 2026-11-09。
 *
 * <p>之所以放在后端解析而不是界面生成：流程通常是定时跑的，
 * 若在界面上算成固定日期，第二天跑就还是旧区间。
 */
public final class DateVarResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile(
            "\\$\\{(today|yesterday|now)(?:([+-])(\\d{1,4})([dmy]))?}",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateVarResolver() {
    }

    public static String resolve(String text) {
        if (StrUtil.isBlank(text) || !text.contains("${")) {
            return text;
        }
        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = evaluate(matcher);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String evaluate(Matcher matcher) {
        String base = matcher.group(1).toLowerCase();
        String sign = matcher.group(2);
        String amountText = matcher.group(3);
        String unit = matcher.group(4);

        // ${now} 保留到秒，便于调试；带偏移的统一按日期输出
        if ("now".equals(base) && sign == null) {
            return LocalDateTime.now().format(DATE_TIME_FORMAT);
        }

        LocalDateTime time = "yesterday".equals(base) ? LocalDateTime.now().minusDays(1) : LocalDateTime.now();
        if (sign != null && amountText != null && unit != null) {
            int amount = Integer.parseInt(amountText);
            boolean plus = "+".equals(sign);
            switch (unit.toLowerCase()) {
                case "d" -> time = plus ? time.plusDays(amount) : time.minusDays(amount);
                case "m" -> time = plus ? time.plusMonths(amount) : time.minusMonths(amount);
                case "y" -> time = plus ? time.plusYears(amount) : time.minusYears(amount);
                default -> {
                }
            }
        }
        return time.toLocalDate().toString();
    }
}
