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

    private AutoInsertSqlBuilder.SyncConfig config(String scopeField, int days,
                                                   String softDeleteColumn, String softDeleteTimeColumn) {
        return new AutoInsertSqlBuilder.SyncConfig(scopeField, days, softDeleteColumn, softDeleteTimeColumn, 0.5);
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
    @DisplayName("解析完整配置")
    void parseFull() {
        String json = JSONUtil.createObj()
                .set("scopeField", "FAuditDate")
                .set("scopeDays", 90)
                .set("softDeleteColumn", "del_flag")
                .set("softDeleteTimeColumn", "del_time")
                .set("maxDeleteRatio", 0.3)
                .toString();
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("FAuditDate", cfg.getScopeField());
        assertEquals(90, cfg.getScopeDays());
        assertEquals("del_flag", cfg.getSoftDeleteColumn());
        assertEquals("del_time", cfg.getSoftDeleteTimeColumn());
        assertEquals(0.3, cfg.getMaxDeleteRatio(), 0.0001);
    }

    @Test
    @DisplayName("未配置软删除列时给默认值")
    void parseDefaults() {
        String json = "{\"scopeField\":\"FAuditDate\",\"scopeDays\":30}";
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("is_deleted", cfg.getSoftDeleteColumn());
        assertEquals("delete_time", cfg.getSoftDeleteTimeColumn());
        assertEquals(0.5, cfg.getMaxDeleteRatio(), 0.0001);
    }

    @Test
    @DisplayName("时间列显式传空串表示不需要")
    void parseWithoutTimeColumn() {
        String json = "{\"scopeField\":\"FAuditDate\",\"scopeDays\":30,\"softDeleteTimeColumn\":\"\"}";
        AutoInsertSqlBuilder.SyncConfig cfg = AutoInsertSqlBuilder.parseSyncConfig(json);
        assertEquals("", cfg.getSoftDeleteTimeColumn());
    }

    @Test
    @DisplayName("缺范围字段 / 窗口非法 / 空配置都要报错")
    void parseInvalid() {
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(null));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig("{}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":0}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":99999}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"1bad\",\"scopeDays\":30}"));
        assertThrows(BizException.class, () -> AutoInsertSqlBuilder.parseSyncConfig("not-json"));
    }

    @Test
    @DisplayName("删除比例越界回落到 0.5")
    void parseRatioFallback() {
        assertEquals(0.5, AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":30,\"maxDeleteRatio\":5}").getMaxDeleteRatio(), 0.0001);
        assertEquals(0.5, AutoInsertSqlBuilder.parseSyncConfig(
                "{\"scopeField\":\"FAuditDate\",\"scopeDays\":30,\"maxDeleteRatio\":-1}").getMaxDeleteRatio(), 0.0001);
    }

    // ===== 脚本生成 =====

    @Test
    @DisplayName("生成「先软删除、后合并」两段脚本")
    void assembleSyncScript() {
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", 90, "is_deleted", "delete_time"), keys());

        // ① 软删除：范围 + 未标记 + 本次缺失
        assertTrue(sql.startsWith("UPDATE `ods_order` o\n"), sql);
        assertTrue(sql.contains("SET o.`is_deleted` = 1, o.`delete_time` = NOW()"), sql);
        assertTrue(sql.contains("WHERE o.`FAuditDate` >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)"), sql);
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
    @DisplayName("不配时间列时不生成时间字段")
    void assembleWithoutTimeColumn() {
        String sql = AutoInsertSqlBuilder.assembleSync(
                new EtlPipeline(), "ods_order", "stg_x",
                matched(), stageIndex(),
                config("FAuditDate", 30, "is_deleted", ""), keys());

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
                config("FAuditDate", 90, "is_deleted", "delete_time"), twoKeys);

        assertTrue(sql.contains("s.`FEntity_FENTRYID` = o.`FEntity_FENTRYID` AND s.`FBillNo` = o.`FBillNo`"), sql);
        assertFalse(sql.contains("`FBillNo`=VALUES(`FBillNo`)"), sql);
    }
}
