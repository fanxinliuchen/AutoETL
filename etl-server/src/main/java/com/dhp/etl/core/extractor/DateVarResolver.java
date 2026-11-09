package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置日期变量替换。
 *
 * <p>只保留三个无参数内置变量：${today}、${yesterday}、${now}。
 *
 * <p>原 ${today-60d} / ${acctWeekStart-1w} 这类偏移写法已废弃：
 * 需要「上个会计周」等复杂口径时，请改用过滤条件面板的「变量定义」，
 * 直接写 SQL 计算，例如：
 * <pre>
 *   startdate = DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 7) DAY)
 *   enddate   = DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 1) DAY)
 * </pre>
 * 然后在条件值里引用 ${startdate}。
 *
 * <p>之所以放在后端解析：流程多为定时任务，界面上算成固定日期第二天就失效了。
 */
public final class DateVarResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile(
            "\\$\\{(today|yesterday|now)}", Pattern.CASE_INSENSITIVE);

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
            matcher.appendReplacement(buffer,
                    Matcher.quoteReplacement(evaluate(matcher.group(1).toLowerCase())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String evaluate(String base) {
        return switch (base) {
            case "yesterday" -> LocalDate.now().minusDays(1).toString();
            case "now" -> LocalDateTime.now().format(DATE_TIME_FORMAT);
            default -> LocalDate.now().toString();
        };
    }
}
