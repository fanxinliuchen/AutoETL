package com.dhp.etl.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * JDBC 结果值归一化：把各方言驱动返回的「对象」收敛成 MySQL 能安全绑定的标准类型。
 *
 * <p>背景：PostgreSQL 的 json / jsonb / uuid / 数组 / bytea 等列，{@code getObject}
 * 返回的是 {@code PGobject}、{@link UUID}、{@code PgArray} 这类对象。MySQL Connector/J
 * 的 {@code setObject} 遇到无法识别的类型（{@code MysqlType.UNKNOWN}）会走
 * {@code setSerializableObject()}，把对象写成 Java 序列化字节流（{@code AC ED 00 05 ...}），
 * 再写进 utf8mb4 文本列时就报
 * {@code Incorrect string value: '\xAC\xED\x00\x05sr...'}。
 *
 * <p>所以这里统一收口：JDBC 标准标量原样保留（不丢精度/时区语义），其余一律转文本。
 * 归一化后既不会触发驱动序列化，也让「临时表 → 目标表」链路与方言无关。
 */
@Slf4j
public final class JdbcValueNormalizer {

    /** 用类名判断，避免为了一个分支引入 PG 驱动的编译期依赖（pom 里是 runtime scope） */
    private static final String PG_OBJECT_CLASS = "org.postgresql.util.PGobject";

    /** bytea 转文本时的最大字节数，防止个别超大二进制列把堆撑爆 */
    private static final int MAX_BINARY_BYTES = 16 * 1024 * 1024;

    private JdbcValueNormalizer() {
    }

    /**
     * @param value {@code ResultSet#getObject} 的原始值
     * @return MySQL 驱动可直接绑定的值；未知类型一律转成文本
     */
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        // 标准标量：MySQL 驱动原生支持，保持不动
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte
                || value instanceof Double || value instanceof Float
                || value instanceof BigDecimal
                || value instanceof Timestamp
                || value instanceof java.sql.Date
                || value instanceof Time) {
            return value;
        }
        // 二进制：按 PostgreSQL 的文本表示输出（\x + 十六进制），落文本列可逆
        if (value instanceof byte[] bytes) {
            return toPgHexLiteral(bytes);
        }
        if (value instanceof UUID) {
            return value.toString();
        }
        // 时间类型：转成 MySQL 认的文本，避免驱动把 LocalXxx 当未知类型
        if (value instanceof LocalDate || value instanceof LocalDateTime || value instanceof LocalTime
                || value instanceof OffsetTime) {
            return value.toString();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return Timestamp.from(offsetDateTime.toInstant());
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return Timestamp.from(zonedDateTime.toInstant());
        }
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        // java.util.Date 非 sql 子类：Connector/J 在某些配置下也会走序列化
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime());
        }
        if (value instanceof Clob clob) {
            return readClob(clob);
        }
        if (value instanceof Blob blob) {
            return toPgHexLiteral(readBlob(blob));
        }
        if (value instanceof SQLXML sqlxml) {
            return readSqlXml(sqlxml);
        }
        if (value instanceof Array array) {
            return arrayText(array);
        }
        if (value instanceof BigInteger bigInteger) {
            // 超出 DECIMAL 范围时转文本，至少不丢值
            return new BigDecimal(bigInteger);
        }
        // PGobject：json / jsonb / xml / money 等，取它的文本值（jsonb 即 JSON 字符串）
        if (PG_OBJECT_CLASS.equals(value.getClass().getName())) {
            return pgObjectText(value);
        }
        // 其它方言对象（PGInterval、几何类型、自定义类型…）：统一取文本，绝不交给驱动序列化
        log.debug("JDBC 值类型 {} 无专用映射，按文本写入", value.getClass().getName());
        return String.valueOf(value);
    }

    /** PGobject.getValue() 返回的就是该列的文本表示；用反射避开 runtime 依赖 */
    private static String pgObjectText(Object value) {
        try {
            Method getValue = value.getClass().getMethod("getValue");
            Object text = getValue.invoke(value);
            return text == null ? null : String.valueOf(text);
        } catch (Exception e) {
            log.debug("读取 PGobject 文本失败，退化为 toString: {}", e.getMessage());
            return String.valueOf(value);
        }
    }

    /** 数组：PG 的 PgArray#toString 已是 {a,"b c",NULL} 这种标准字面量 */
    private static String arrayText(Array array) {
        try {
            String text = String.valueOf(array);
            String identity = array.getClass().getName() + "@";
            if (!text.startsWith(identity)) {
                return text;
            }
            Object raw = array.getArray();
            if (raw instanceof Object[] items) {
                StringJoiner joiner = new StringJoiner(",", "{", "}");
                for (Object item : items) {
                    joiner.add(item == null ? "NULL" : String.valueOf(item));
                }
                return joiner.toString();
            }
            return text;
        } catch (Exception e) {
            log.debug("数组转文本失败: {}", e.getMessage());
            return String.valueOf(array);
        }
    }

    private static String readClob(Clob clob) {
        try {
            long length = clob.length();
            if (length > MAX_BINARY_BYTES) {
                length = MAX_BINARY_BYTES;
            }
            return clob.getSubString(1, (int) length);
        } catch (Exception e) {
            log.debug("读取 CLOB 失败: {}", e.getMessage());
            return String.valueOf(clob);
        }
    }

    private static byte[] readBlob(Blob blob) {
        try {
            long length = Math.min(blob.length(), MAX_BINARY_BYTES);
            return blob.getBytes(1, (int) length);
        } catch (Exception e) {
            log.debug("读取 BLOB 失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    private static String readSqlXml(SQLXML sqlxml) {
        try {
            return sqlxml.getString();
        } catch (Exception e) {
            log.debug("读取 SQLXML 失败: {}", e.getMessage());
            return String.valueOf(sqlxml);
        }
    }

    /** PostgreSQL 的 bytea 文本形态：\x 前缀 + 小写十六进制 */
    static String toPgHexLiteral(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("\\x");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
