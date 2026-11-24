package com.dhp.etl.core.guard;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL 源 SQL 守卫：只做文本形状与高危操作判断。
 *
 * <p>方言语法（:: 转换、ILIKE、DISTINCT ON）不能靠 JSqlParser，
 * 真正的语法/权限校验放在运行时的 EXPLAIN。
 */
class PostgresSqlGuardTest {

    private final PostgresSqlGuard guard = new PostgresSqlGuard();

    @Test
    @DisplayName("SELECT / WITH 与 PG 方言写法可以通过")
    void selectLikePass() {
        assertDoesNotThrow(() -> guard.validateShape(
                "SELECT id, title::text AS t FROM nc_records WHERE title ILIKE '%a%'"));
        assertDoesNotThrow(() -> guard.validateShape(
                "WITH x AS (SELECT 1 AS a) SELECT a FROM x"));
        assertDoesNotThrow(() -> guard.validateShape(
                "-- 取数\nSELECT * FROM \"p04byh5cgqtkg6q\".\"菏泽精板呆滞品填报\""));
        // 字符串/引号标识符里的分号不能被误判成多语句
        assertDoesNotThrow(() -> guard.validateShape("SELECT 'a;b' AS x, \"c;d\" FROM t"));
        assertDoesNotThrow(() -> guard.validateShape("SELECT 1 AS a;"));
    }

    @Test
    @DisplayName("写操作与非查询语句被拒绝")
    void nonQueryRejected() {
        assertThrows(BizException.class, () -> guard.validateShape("UPDATE t SET a = 1"));
        assertThrows(BizException.class, () -> guard.validateShape("DELETE FROM t"));
        assertThrows(BizException.class, () -> guard.validateShape("INSERT INTO t VALUES (1)"));
        assertThrows(BizException.class, () -> guard.validateShape("DROP DATABASE nocodb"));
        assertThrows(BizException.class, () -> guard.validateShape("GRANT ALL ON SCHEMA public TO x"));
        assertThrows(BizException.class, () -> guard.validateShape("SELECT * FROM t; DROP TABLE t"));
    }

    @Test
    @DisplayName("高危函数被拒绝")
    void dangerousFunctionsRejected() {
        assertThrows(BizException.class, () -> guard.validateShape("SELECT pg_read_file('/etc/passwd')"));
        assertThrows(BizException.class, () -> guard.validateShape("SELECT * FROM dblink('host=x', 'select 1') AS t(a int)"));
    }
}
