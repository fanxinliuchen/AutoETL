package com.dhp.etl.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * JDBC 值归一化测试：目标是不让任何方言对象被 MySQL 驱动序列化。
 *
 * <p>回归背景：PG jsonb 列返回 PGobject，直接 setObject 会被 Connector/J
 * 写成 Java 序列化字节流（AC ED 00 05），utf8mb4 文本列直接报
 * "Incorrect string value: '\xAC\xED\x00\x05sr...'"。
 */
class JdbcValueNormalizerTest {

    @Test
    @DisplayName("标准标量原样返回，不改变精度与类型")
    void keepsStandardScalars() {
        assertNull(JdbcValueNormalizer.normalize(null));

        assertSame("abc", JdbcValueNormalizer.normalize("abc"));
        assertSame(42, JdbcValueNormalizer.normalize(42));
        assertEquals(1234L, JdbcValueNormalizer.normalize(1234L));
        assertSame(true, JdbcValueNormalizer.normalize(true));

        BigDecimal decimal = new BigDecimal("12.3400");
        assertSame(decimal, JdbcValueNormalizer.normalize(decimal));

        Timestamp ts = Timestamp.valueOf("2026-11-20 08:00:00");
        assertSame(ts, JdbcValueNormalizer.normalize(ts));
    }

    @Test
    @DisplayName("PG jsonb：PGobject 取文本值，得到 JSON 字符串")
    void pgObjectToJsonText() throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue("{\"a\": 1, \"中文\": \"值\"}");

        Object normalized = JdbcValueNormalizer.normalize(jsonb);

        assertInstanceOf(String.class, normalized);
        assertEquals("{\"a\": 1, \"中文\": \"值\"}", normalized);
    }

    @Test
    @DisplayName("PG uuid / 数组 / bytea 都能转成文本")
    void pgSpecificTypes() {
        UUID uuid = UUID.fromString("180ecd4a-fd5d-44b5-be78-a6efe4a7e041");
        assertEquals(uuid.toString(), JdbcValueNormalizer.normalize(uuid));

        Array array = new StubArray(new String[]{"a", "b c", null});
        assertEquals("{a,b c,NULL}", JdbcValueNormalizer.normalize(array));

        byte[] bytes = new byte[]{0x0A, (byte) 0xFF};
        assertEquals("\\x0aff", JdbcValueNormalizer.normalize(bytes));
    }

    @Test
    @DisplayName("时间类型统一成 MySQL 认的 Timestamp / 文本")
    void timeTypes() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 11, 20, 8, 0, 0, 0, ZoneOffset.ofHours(8));
        Object normalized = JdbcValueNormalizer.normalize(odt);
        assertInstanceOf(Timestamp.class, normalized);
        assertEquals(odt.toInstant().toEpochMilli(), ((Timestamp) normalized).getTime());

        assertEquals("2026-11-20", JdbcValueNormalizer.normalize(LocalDate.of(2026, 11, 20)));

        java.util.Date utilDate = new java.util.Date(1_700_000_000_000L);
        assertInstanceOf(Timestamp.class, JdbcValueNormalizer.normalize(utilDate));
    }

    @Test
    @DisplayName("未知的可序列化对象转文本，绝不落成 Java 序列化字节")
    void unknownSerializableToText() {
        Object normalized = JdbcValueNormalizer.normalize(new CustomType("v1"));

        assertEquals("CustomType(v1)", normalized);
        assertEquals(String.class, normalized.getClass());
    }

    @Test
    @DisplayName("Map 等结构也不会被序列化")
    void mapToText() {
        Object normalized = JdbcValueNormalizer.normalize(Map.of("k", "v"));
        assertEquals(String.class, normalized.getClass());
    }

    /** 满足 java.sql.Array 的最小实现，用来模拟 PgArray */
    private record StubArray(Object[] items) implements Array {
        @Override
        public String getBaseTypeName() {
            return "text";
        }

        @Override
        public int getBaseType() {
            return java.sql.Types.VARCHAR;
        }

        @Override
        public Object getArray() {
            return items;
        }

        @Override
        public Object getArray(Map<String, Class<?>> map) {
            return items;
        }

        @Override
        public Object getArray(long index, int count) {
            return items;
        }

        @Override
        public Object getArray(long index, int count, Map<String, Class<?>> map) {
            return items;
        }

        @Override
        public java.sql.ResultSet getResultSet() {
            return null;
        }

        @Override
        public java.sql.ResultSet getResultSet(Map<String, Class<?>> map) {
            return null;
        }

        @Override
        public java.sql.ResultSet getResultSet(long index, int count) {
            return null;
        }

        @Override
        public java.sql.ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) {
            return null;
        }

        @Override
        public void free() {
            // no-op
        }

        @Override
        public String toString() {
            return "{a,b c,NULL}";
        }
    }

    private record CustomType(String value) implements Serializable {
        @Override
        public String toString() {
            return "CustomType(" + value + ")";
        }
    }
}
