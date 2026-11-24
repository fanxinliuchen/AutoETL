package com.dhp.etl.core.extractor;

import com.dhp.etl.util.FieldMetaResolver;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * JDBC 结果集元数据 → 项目归一化列类型。
 *
 * <p>先按 {@link Types} 判定类型，再用「首行样例值」修正 JDBC 元数据拿不准的情况：
 * 表达式列没有长度、DECIMAL 无精度/小数位、驱动把类型报成 OTHER 等。
 * 最终统一过 {@link FieldMetaResolver#normalizeType(String)} 白名单，保证建表不会出现非法类型。
 */
public final class SourceColumnTypeMapper {

    private SourceColumnTypeMapper() {
    }

    /**
     * @param jdbcType  ResultSetMetaData#getColumnType
     * @param precision 列长度（字符列）或数值精度
     * @param scale     小数位
     * @param sample    首行样例值，可为 null（SQL 无数据时）
     */
    public static String infer(int jdbcType, int precision, int scale, Object sample) {
        String candidate = switch (jdbcType) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER, Types.SMALLINT -> "INT";
            case Types.TINYINT, Types.BIT, Types.BOOLEAN -> "TINYINT";
            case Types.DECIMAL, Types.NUMERIC -> decimalType(precision, scale, sample);
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE";
            case Types.DATE -> "DATE";
            case Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME";
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR -> varcharType(precision, sample);
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB,
                 Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "TEXT";
            default -> inferFromSample(sample);
        };
        return FieldMetaResolver.normalizeType(candidate);
    }

    /**
     * DECIMAL：元数据缺精度/小数位时给安全默认值，并收敛到白名单允许的范围。
     *
     * <p>注意：PostgreSQL 的 {@code numeric} 不带 typmod 时，元数据精度恒为 0，
     * 这时**不能**按首行样例收紧——同一列后续行的位数可能比样例多
     * （如 nc_order：样例 0.25、实际还有 0.125 和 30，按样例算成 DECIMAL(3,2) 会溢出）。
     * 因此精度未知时统一按 18 位、小数位至少 4 位起步。
     */
    private static String decimalType(int precision, int scale, Object sample) {
        int p = precision;
        int s = Math.max(scale, 0);
        int sampleScale = 0;
        int sampleIntDigits = 0;
        if (sample instanceof BigDecimal bd) {
            sampleScale = Math.max(bd.scale(), 0);
            sampleIntDigits = Math.max(bd.precision() - bd.scale(), 1);
        }
        if (p <= 0) {
            // 精度未知（PG numeric / 表达式列）
            s = Math.max(s, Math.max(sampleScale, 4));
            p = Math.max(18, sampleIntDigits + s);
        } else if (s == 0 && sampleScale > 0) {
            // 元数据给了精度但没给小数位：按样例补小数位，并保证整数位放得下
            s = Math.min(sampleScale, 10);
            p = Math.max(p, sampleIntDigits + s);
        }
        if (s > 10) {
            s = 10;
        }
        if (p > 30) {
            p = 30;
        }
        if (s > p) {
            s = p;
        }
        return "DECIMAL(" + p + "," + s + ")";
    }

    /** 字符列：优先用元数据长度；表达式列长度为 0 时按样例长度兜底（不小于 32） */
    private static String varcharType(int precision, Object sample) {
        int len = precision;
        if (len <= 0) {
            int sampleLen = sample == null ? 0 : textLength(String.valueOf(sample));
            len = Math.max(32, Math.min(sampleLen, 2000));
        }
        if (len > 2000) {
            return "TEXT";
        }
        return "VARCHAR(" + len + ")";
    }

    /** 驱动报 OTHER/NULL 等未识别类型时，完全按样例值判断 */
    private static String inferFromSample(Object sample) {
        if (sample == null) {
            return "VARCHAR(255)";
        }
        if (sample instanceof BigDecimal bd) {
            return decimalType(0, 0, bd);
        }
        if (sample instanceof Byte || sample instanceof Short
                || sample instanceof Integer || sample instanceof Long) {
            return "BIGINT";
        }
        if (sample instanceof Float || sample instanceof Double) {
            return "DOUBLE";
        }
        if (sample instanceof Boolean) {
            return "TINYINT";
        }
        // PostgreSQL 常见类型：uuid / json(jsonb) / 带时区时间戳
        if (sample instanceof UUID) {
            return "VARCHAR(64)";
        }
        // 不引入 PG 驱动编译期依赖：json/jsonb 等类型由驱动返回 PGobject
        if (sample != null && "org.postgresql.util.PGobject".equals(sample.getClass().getName())) {
            return "TEXT";
        }
        if (sample instanceof OffsetDateTime || sample instanceof Instant) {
            return "DATETIME";
        }
        if (sample instanceof java.sql.Date || sample instanceof LocalDate) {
            return "DATE";
        }
        if (sample instanceof java.sql.Timestamp || sample instanceof LocalDateTime
                || sample instanceof java.sql.Time || sample instanceof LocalTime) {
            return "DATETIME";
        }
        if (sample instanceof byte[]) {
            return "TEXT";
        }
        int len = textLength(String.valueOf(sample));
        if (len > 2000) {
            return "TEXT";
        }
        return "VARCHAR(" + Math.max(32, len) + ")";
    }

    private static int textLength(String text) {
        return text.codePointCount(0, text.length());
    }
}
