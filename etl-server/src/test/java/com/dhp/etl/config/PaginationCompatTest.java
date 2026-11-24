package com.dhp.etl.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus 分页与 JSqlParser 的兼容性回归。
 *
 * <p>MP 3.5.12 的分页拦截器拆到 mybatis-plus-jsqlparser 模块，内部仍要走 JSqlParser
 * 生成 count SQL。这条用例直接调用 autoCountSql，确保升级后分页解析不抛
 * NoSuchMethodError / 语法异常（历史坑：JSqlParser 4.7 移除了 SelectBody）。
 */
class PaginationCompatTest {

    @Test
    @DisplayName("分页 count SQL 能正常解析生成")
    void autoCountSqlWorks() {
        PaginationInnerInterceptor interceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        Page<?> page = new Page<>(1, 10);
        String countSql = interceptor.autoCountSql(page,
                "SELECT id, pipeline_name, target_table FROM t_etl_pipeline "
                        + "WHERE object_status_cd = 0 ORDER BY update_time DESC");
        assertTrue(countSql.toLowerCase().contains("count("), "应生成 count 语句，实际: " + countSql);
    }

    @Test
    @DisplayName("带 JOIN 的分页 SQL 也能生成 count")
    void autoCountSqlWithJoin() {
        PaginationInnerInterceptor interceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        Page<?> page = new Page<>(1, 10);
        String countSql = interceptor.autoCountSql(page,
                "SELECT a.id, b.ds_name FROM t_etl_pipeline a "
                        + "LEFT JOIN t_etl_datasource b ON a.source_ds_id = b.id "
                        + "WHERE a.object_status_cd = 0 GROUP BY a.id, b.ds_name");
        assertTrue(countSql.toLowerCase().contains("count("), "应生成 count 语句，实际: " + countSql);
    }
}
