package com.dhp.etl.core.guard;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SQL 安全守卫测试。
 *
 * <p>重点覆盖中文标识符：JSqlParser 4.6 的词法器只认 ASCII 标识符，
 * 裸中文列名（如 t2.公司简称）会抛 TokenMgrException；4.7 起支持 CJK，
 * 这里守住「中文列名/中文别名不再被误判为语法错误」这条基线。
 */
class SqlSafetyGuardTest {

    private final SqlSafetyGuard guard = new SqlSafetyGuard();

    @Test
    @DisplayName("裸中文列名与中文别名可以通过语法校验")
    void chineseIdentifiersPass() {
        String sql = """
                SELECT
                REPLACE(REPLACE(REPLACE(t1.f23, '（', '('), '）', ')'), ' ', '') AS '企业全称',
                t2.公司简称 AS '企业简称',
                COUNT(DISTINCT CASE WHEN t1.f1 > 0 THEN DATE(t1.f19) END) AS '本月生产天数'
                FROM ods_mes_suban_dailyreport t1
                LEFT JOIN ods_nocodb_jichu_jiancheng t2
                ON REPLACE(REPLACE(REPLACE(t1.f23, '（', '('), '）', ')'), ' ', '') =
                   REPLACE(REPLACE(REPLACE(t2.公司名称, '（', '('), '）', ')'), ' ', '')
                WHERE t1.f23 != ''
                GROUP BY REPLACE(REPLACE(REPLACE(t1.f23, '（', '('), '）', ')'), ' ', ''), t2.公司简称
                """;
        assertDoesNotThrow(() -> guard.validate(sql));
    }

    @Test
    @DisplayName("中文表名/列名的 INSERT、UPDATE 语句同样可以解析")
    void chineseDmlPass() {
        assertDoesNotThrow(() -> guard.validate(
                "INSERT INTO ods_企业日报 (企业全称, 统计日期) VALUES ('a', '2026-11-17')"));
        assertDoesNotThrow(() -> guard.validate(
                "UPDATE ods_企业日报 SET 企业简称 = 'b' WHERE 企业全称 = 'a'"));
    }

    @Test
    @DisplayName("动态变量占位符替换后不影响解析")
    void variablesPass() {
        assertDoesNotThrow(() -> guard.validate(
                "SELECT * FROM ods_${stage_table} WHERE dt = '${today}'"));
    }

    @Test
    @DisplayName("高危语句仍被拦截")
    void forbiddenStillBlocked() {
        assertThrows(BizException.class, () -> guard.validate("GRANT ALL ON *.* TO 'x'@'%'"));
        assertThrows(BizException.class, () -> guard.validate("SELECT * INTO OUTFILE '/tmp/a' FROM t"));
        // DROP TABLE 不在白名单语句类型内
        assertThrows(BizException.class, () -> guard.validate("DROP TABLE ods_x"));
    }
}
