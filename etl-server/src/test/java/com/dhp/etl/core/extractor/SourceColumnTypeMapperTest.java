package com.dhp.etl.core.extractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MySQL 抽取字段类型推断：JDBC 元数据 + 首行样例修正。
 *
 * <p>样例值的意义在于补元数据的缺口：表达式列长度为 0、DECIMAL 无精度/小数位、
 * 驱动把类型报成 OTHER 等。
 */
class SourceColumnTypeMapperTest {

    @Test
    @DisplayName("常规类型直接按 JDBC 元数据映射")
    void basicTypes() {
        assertEquals("BIGINT", SourceColumnTypeMapper.infer(Types.BIGINT, 20, 0, 1L));
        assertEquals("INT", SourceColumnTypeMapper.infer(Types.INTEGER, 10, 0, 1));
        assertEquals("TINYINT", SourceColumnTypeMapper.infer(Types.BIT, 1, 0, true));
        assertEquals("DOUBLE", SourceColumnTypeMapper.infer(Types.DOUBLE, 22, 0, 1.5d));
        assertEquals("DATE", SourceColumnTypeMapper.infer(Types.DATE, 10, 0, LocalDate.now()));
        assertEquals("DATETIME", SourceColumnTypeMapper.infer(Types.TIMESTAMP, 19, 0, LocalDateTime.now()));
        assertEquals("TEXT", SourceColumnTypeMapper.infer(Types.BLOB, 65535, 0, new byte[]{1}));
    }

    @Test
    @DisplayName("字符列：元数据长度优先，长度为 0 时用样例长度兜底")
    void varcharTypes() {
        assertEquals("VARCHAR(64)", SourceColumnTypeMapper.infer(Types.VARCHAR, 64, 0, "abc"));
        // 表达式列（如 CONCAT(...)）驱动常给 0 长度
        assertEquals("VARCHAR(32)", SourceColumnTypeMapper.infer(Types.VARCHAR, 0, 0, "abc"));
        assertEquals("VARCHAR(200)", SourceColumnTypeMapper.infer(Types.VARCHAR, 0, 0, "x".repeat(200)));
        // 超长降 TEXT，避免采样值短就截断别的行
        assertEquals("TEXT", SourceColumnTypeMapper.infer(Types.VARCHAR, 5000, 0, "abc"));
    }

    @Test
    @DisplayName("DECIMAL：元数据缺精度时给安全默认值，不按样例收紧")
    void decimalTypes() {
        assertEquals("DECIMAL(10,2)", SourceColumnTypeMapper.infer(Types.DECIMAL, 10, 2, new BigDecimal("12.34")));
        // PG numeric 无 typmod / 表达式列：精度未知，按 18 位、至少 4 位小数起步，
        // 避免样例 0.25 算成 DECIMAL(3,2)，后续行 30 或 0.125 就溢出
        assertEquals("DECIMAL(18,4)", SourceColumnTypeMapper.infer(Types.DECIMAL, 0, 0, new BigDecimal("12.34")));
        assertEquals("DECIMAL(18,4)", SourceColumnTypeMapper.infer(Types.DECIMAL, 0, 0, new BigDecimal("0.25")));
        assertEquals("DECIMAL(18,4)", SourceColumnTypeMapper.infer(Types.DECIMAL, 0, 0, new BigDecimal("30")));
        // 精度越界收敛到 30 位、10 位小数
        assertEquals("DECIMAL(30,10)", SourceColumnTypeMapper.infer(Types.DECIMAL, 65, 30, null));
    }

    @Test
    @DisplayName("未识别类型按样例值判断，无样例时回退 VARCHAR(255)")
    void sampleFallback() {
        assertEquals("DATE", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, LocalDate.now()));
        assertEquals("DATETIME", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, LocalDateTime.now()));
        assertEquals("BIGINT", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, 100L));
        assertEquals("VARCHAR(32)", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, "中文"));
        assertEquals("VARCHAR(255)", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, null));
    }

    @Test
    @DisplayName("PostgreSQL 类型：uuid / jsonb / 带时区时间戳 / bool")
    void postgresTypes() throws Exception {
        assertEquals("VARCHAR(64)", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, UUID.randomUUID()));
        assertEquals("DATETIME", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, OffsetDateTime.now()));
        assertEquals("TINYINT", SourceColumnTypeMapper.infer(Types.BOOLEAN, 0, 0, true));
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue("{\"a\":1}");
        assertEquals("TEXT", SourceColumnTypeMapper.infer(Types.OTHER, 0, 0, json));
    }
}
