package com.dhp.etl.core.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时表写入：空值归一
 *
 * <p>金蝶对空值返回的是 " "（一个空格），落 INT / DECIMAL / DATE 列会被 MySQL 严格模式拒掉
 * （Incorrect integer value: ' '），写入前必须归一成 NULL。
 */
class MySqlDwLoaderTest {

    @Test
    @DisplayName("非文本列的空串/空格按 NULL 处理")
    void blankToNullForNonTextColumns() {
        assertTrue(MySqlDwLoader.isBlankForColumn(" ", "INT"));
        assertTrue(MySqlDwLoader.isBlankForColumn("", "BIGINT"));
        assertTrue(MySqlDwLoader.isBlankForColumn("   ", "DECIMAL(18,4)"));
        assertTrue(MySqlDwLoader.isBlankForColumn(" ", "DATETIME"));
    }

    @Test
    @DisplayName("文本列的空值原样保留，不改成 NULL")
    void keepBlankForTextColumns() {
        assertFalse(MySqlDwLoader.isBlankForColumn(" ", "VARCHAR(255)"));
        assertFalse(MySqlDwLoader.isBlankForColumn("", "TEXT"));
        assertFalse(MySqlDwLoader.isBlankForColumn(" ", "VARCHAR(32)"));
    }

    @Test
    @DisplayName("非空值与 null 都不动")
    void keepOthers() {
        assertFalse(MySqlDwLoader.isBlankForColumn("1", "INT"));
        assertFalse(MySqlDwLoader.isBlankForColumn(null, "INT"));
        assertFalse(MySqlDwLoader.isBlankForColumn(1, "INT"));
    }

    @Test
    @DisplayName("文本类型判定")
    void textColumnDetection() {
        assertTrue(MySqlDwLoader.isTextColumn(null));
        assertTrue(MySqlDwLoader.isTextColumn(""));
        assertTrue(MySqlDwLoader.isTextColumn("VARCHAR(255)"));
        assertTrue(MySqlDwLoader.isTextColumn("varchar(32)"));
        assertTrue(MySqlDwLoader.isTextColumn("LONGTEXT"));
        assertFalse(MySqlDwLoader.isTextColumn("INT"));
        assertFalse(MySqlDwLoader.isTextColumn("DECIMAL(18,4)"));
        assertFalse(MySqlDwLoader.isTextColumn("DATE"));
    }
}
