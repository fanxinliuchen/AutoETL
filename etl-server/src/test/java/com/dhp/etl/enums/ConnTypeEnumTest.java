package com.dhp.etl.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接类型语义：JDBC 类（MySQL/PostgreSQL）能拿连接池，API 类走鉴权会话。
 * 这条区分决定了「能不能当数据库源库」，PG 接入最容易在这里踩坑。
 */
class ConnTypeEnumTest {

    @Test
    @DisplayName("PostgreSQL 属于 JDBC 连接，不是 API 连接")
    void postgresIsJdbc() {
        assertTrue(ConnTypeEnum.POSTGRESQL.isJdbc());
        assertFalse(ConnTypeEnum.POSTGRESQL.isApi());
        assertTrue(ConnTypeEnum.MYSQL.isJdbc());
    }

    @Test
    @DisplayName("金蝶/云表仍是 API 连接")
    void apiTypes() {
        assertTrue(ConnTypeEnum.KINGDEE_K3.isApi());
        assertTrue(ConnTypeEnum.IYUNBIAO.isApi());
        assertFalse(ConnTypeEnum.KINGDEE_K3.isJdbc());
    }
}
