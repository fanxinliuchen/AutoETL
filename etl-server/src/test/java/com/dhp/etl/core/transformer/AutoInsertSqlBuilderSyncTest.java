package com.dhp.etl.core.transformer;

import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.etl.entity.EtlPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 增量同步（SYNC）：配置解析与脚本生成
 */
class AutoInsertSqlBuilderSyncTest {

    private AutoInsertSqlBuilder.SyncConfig config(String scopeField, String start, String end,
                                                   String softDeleteColumn, String softDeleteTimeColumn) {
        return new AutoInsertSqlBuilder.SyncConfig(scopeField, start, end,
                softDeleteColumn, softDeleteTimeColumn, 0.5);
    }

    private Map<String, String> stageIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        index.put("fentity_fentryid", "FEntity_FENTRYID");
        index.put("fbillno", "FBillNo");
        index.put("fqty", "FQty");
        return index;
    }

    private List<String> matched() {
        return List.of("FEntity_FENTRYID", "FBillNo", "FQty");
    }

    private Set<String> keys() {
        return new LinkedHashSet<>(List.of("fentity_fentryid"));
    }

    // ===== 配置解析 =====

    @Test
    @DisplayName("解析完整配置：范围起止原样保留，取值交给渲染阶段")
    void parseFull() {
        String json = JSONUtil.createObj()
                .set("scopeField", "FAuditDate")
                .set("scopeStart", "${begindate}")
                .set("scopeEnd", "${enddate}")
                .set("softDeleteColumn", "del_flag")
                .set("softDeleteTimeColumn", "del_time")
                .set("maxDeleteRatio", 0.3)
                .toString();
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("FAuditDate", cfg.getScopeField());
        assertEquals("${begindate}", cfg.getScopeStart());
        assertEquals("${enddate}", cfg.getScopeEnd());
        assertEquals("del_flag", cfg.getSoftDeleteColumn());
        assertEquals("del_time", cfg.getSoftDeleteTimeColumn());
        assertEquals(0.3, cfg.getMaxDeleteRatio(), 0.0001);
    }

    @Test
    @DisplayName("未配置软删除列时给默认值")
    void parseDefaults() {
        String json = "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"2026-11-01\"}";
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("is_deleted", cfg.getSoftDeleteColumn());
        assertEquals("delete_time", cfg.getSoftDeleteTimeColumn());
        assertEquals(0.5, cfg.getMaxDeleteRatio(), 0.0001);
        assertEquals("", cfg.getScopeEnd());
    }

    @Test
    @DisplayName("时间列显式传空串表示不需要")
    void parseWithoutTimeColumn() {
        String json = "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"2026-11-01\",\"softDeleteTimeColumn\":\"\"}";
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("", cfg.getSoftDeleteTimeColumn());
    }

    @Test
    @DisplayName("旧配置只有窗口天数时折算成「近 N 天」的起点")
    void parseLegacyScopeDays() {
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":90}");
        assertEquals("DATE_SUB(CURDATE(), INTERVAL 90 DAY)", cfg.getScopeStart());
        assertEquals("", cfg.getScopeEnd());
        // 渲染后是原生表达式，不加引号
        assertEquals("`FAuditDate` >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)", cfg.scopeCondition(null));
    }

    @Test
    @DisplayName("缺范围字段 / 范围全空 / 非法取值都要报错")
    void parseInvalid() {
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(null));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig("{}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"\",\"scopeEnd\":\"\"}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":0}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":99999}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"1bad\",\"scopeStart\":\"2026-11-01\"}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"2026-11-01; DROP TABLE x\"}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"" + "9".repeat(201) + "\"}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig("not-json"));
    }

    @Test
    @DisplayName("删除比例越界回落到 0.5")
    void parseRatioFallback() {
        assertEquals(0.5, AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"2026-11-01\",\"maxDeleteRatio\":5}")
                .getMaxDeleteRatio(), 0.0001);
        assertEquals(0.5, AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeStart\":\"2026-11-01\",\"maxDeleteRatio\":-1}")
                .getMaxDeleteRatio(), 0.0001);
    }

    // ===== 范围取值渲染 =====

    @Test
    @DisplayName("常量按日期加引号，表达式与数字原样，空值不设限")
    void renderBound() {
        assertEquals("'2026-11-01'", AutoInsertSqlBuilder.renderBound("2026-11-01"));
        assertEquals("'2026-11-01 23:59:59'", AutoInsertSqlBuilder.renderBound(" 2026-11-01 23:59:59 "));
        assertEquals("'O''Brien'", AutoInsertSqlBuilder.renderBound("O'Brien"));
        assertEquals("20261101", AutoInsertSqlBuilder.renderBound("20261101"));
        assertEquals("DATE_SUB(CURDATE(), INTERVAL 90 DAY)",
                AutoInsertSqlBuilder.renderBound("DATE_SUB(CURDATE(), INTERVAL 90 DAY)"));
        assertNull(AutoInsertSqlBuilder.renderBound(null));
        assertNull(AutoInsertSqlBuilder.renderBound("   "));
    }

    @Test
    @DisplayName("表达式里的变量先包成字符串字面量，避免替换后日期缺引号")
    void prepareBound() {
        assertEquals("DATE_SUB('${enddate}', INTERVAL 1 DAY)",
                AutoInsertSqlBuilder.prepareBound("DATE_SUB(${enddate}, INTERVAL 1 DAY)"));
        // 整个取值就是一个变量时不预包，交给 renderBound 统一加引号
        assertEquals("${begindate}", AutoInsertSqlBuilder.prepareBound("${begindate}"));
        // 纯常量、无常量的表达式都原样返回
        assertEquals("2026-11-01", AutoInsertSqlBuilder.prepareBound(" 2026-11-01 "));
        assertEquals("DATE_SUB(CURDATE(), INTERVAL 90 DAY)",
                AutoInsertSqlBuilder.prepareBound("DATE_SUB(CURDATE(), INTERVAL 90 DAY)"));
        assertEquals("", AutoInsertSqlBuilder.prepareBound(null));
    }

    @Test
    @DisplayName("变量解析结果回填后覆盖原文")
    void withScopeExpr() {
        AutoInsertSqlBuilder.SyncConfig raw = config("FAuditDate", "${begindate}", "${enddate}",
                "is_deleted", "delete_time");
        AutoInsertSqlBuilder.SyncConfig resolved = raw.withScopeExpr("'2026-11-01'", "'2026-11-07'");
        assertEquals("`FAuditDate` >= '2026-11-01' AND `FAuditDate` < '2026-11-07'",
                resolved.scopeCondition(null));
        assertEquals("o.`FAuditDate` >= '2026-11-01' AND o.`FAuditDate` < '2026-11-07'",
                resolved.scopeCondition("o"));
        // 原文保留，便于日志核对
        assertEquals("${begindate}", resolved.getScopeStart());
    }

    // ===== 脚本生成 =====

    @Test
    @DisplayName("生成「先软删除、后合并」两段脚本")
    void assembleSyncScript() {
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", "2026-11-01", "2026-11-10", "is_deleted", "delete_time"), keys());

        // ① 软删除：范围 + 未标记 + 本次缺失
        assertTrue(sql.startsWith("UPDATE `ods_order` o\n"), sql);
        assertTrue(sql.contains("SET o.`is_deleted` = 1, o.`delete_time` = NOW()"), sql);
        assertTrue(sql.contains("WHERE o.`FAuditDate` >= '2026-11-01' AND o.`FAuditDate` < '2026-11-10'"), sql);
        assertTrue(sql.contains("(o.`is_deleted` = 0 OR o.`is_deleted` IS NULL)"), sql);
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM `stg_x` s WHERE s.`FEntity_FENTRYID` = o.`FEntity_FENTRYID`)"), sql);

        // ② 合并写入：带上软删除列初值
        assertTrue(sql.contains("INSERT INTO `ods_order` (`FEntity_FENTRYID`, `FBillNo`, `FQty`, `is_deleted`, `delete_time`)"), sql);
        assertTrue(sql.contains("SELECT `FEntity_FENTRYID`, `FBillNo`, `FQty`, 0, NULL FROM `stg_x`"), sql);

        // ③ 已存在则更新非主键列，并把删除标记复位
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE `FBillNo`=VALUES(`FBillNo`), `FQty`=VALUES(`FQty`)"), sql);
        assertTrue(sql.contains("`is_deleted`=0"), sql);
        assertTrue(sql.contains("`delete_time`=NULL"), sql);
        assertFalse(sql.contains("`FEntity_FENTRYID`=VALUES(`FEntity_FENTRYID`)"), "主键不应出现在更新列表");
    }

    @Test
    @DisplayName("只填起点时不生成终点条件")
    void assembleWithoutEnd() {
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", "2026-11-01", "", "is_deleted", "delete_time"), keys());

        assertTrue(sql.contains("WHERE o.`FAuditDate` >= '2026-11-01'\n  AND (o.`is_deleted`"), sql);
        assertFalse(sql.contains(" < "), sql);
    }

    @Test
    @DisplayName("不配时间列时不生成时间字段")
    void assembleWithoutTimeColumn() {
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", "2026-11-01", "2026-11-10", "is_deleted", ""), keys());

        assertTrue(sql.contains("SET o.`is_deleted` = 1\n"), sql);
        assertFalse(sql.contains("delete_time"), sql);
    }

    @Test
    @DisplayName("多主键用 AND 连接，全部主键都不出现在更新列表")
    void assembleMultiKeys() {
        Set<String> twoKeys = new LinkedHashSet<>(List.of("fentity_fentryid", "fbillno"));
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", "2026-11-01", "2026-11-10", "is_deleted", "delete_time"), twoKeys);

        assertTrue(sql.contains("s.`FEntity_FENTRYID` = o.`FEntity_FENTRYID` AND s.`FBillNo` = o.`FBillNo`"), sql);
        assertFalse(sql.contains("`FBillNo`=VALUES(`FBillNo`)"), sql);
    }
}
