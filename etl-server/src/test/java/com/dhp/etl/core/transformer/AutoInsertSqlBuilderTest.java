package com.dhp.etl.core.transformer;

import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.WriteModeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 未配置转换 SQL 时的自动入库脚本生成。
 *
 * <p>UPSERT 的冲突键取目标表真实主键（targetKeyColumns），界面不再手填 upsertKeys，
 * 所以这里只验证「主键列不进 UPDATE 列表」这一层。
 */
class AutoInsertSqlBuilderTest {

    private EtlPipeline pipeline(WriteModeEnum mode) {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setTargetTable("dwd_order");
        pipeline.setWriteMode(mode);
        return pipeline;
    }

    private Map<String, String> stageIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        index.put("fbillno", "FBillNo");
        index.put("fdate", "FDate");
        index.put("fqty", "FQty");
        return index;
    }

    @Test
    @DisplayName("APPEND：显式列名直通，不使用 SELECT *")
    void appendMode() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.APPEND),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate"), stageIndex(), List.of());

        assertEquals("INSERT INTO `dwd_order` (`FBillNo`, `FDate`)\n"
                + "SELECT `FBillNo`, `FDate` FROM `stg_pl_1001`", sql);
        assertFalse(sql.contains("*"), "不应生成 SELECT *");
    }

    @Test
    @DisplayName("OVERWRITE：先 TRUNCATE 再插入")
    void overwriteMode() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.OVERWRITE),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex(), List.of());

        assertTrue(sql.startsWith("TRUNCATE TABLE `dwd_order`;\n"), sql);
        assertTrue(sql.contains("INSERT INTO `dwd_order` (`FBillNo`)"), sql);
    }

    @Test
    @DisplayName("UPSERT：非主键列生成 ON DUPLICATE KEY UPDATE")
    void upsertMode() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate", "FQty"), stageIndex(), List.of("FBillNo"));

        assertTrue(sql.endsWith(
                "ON DUPLICATE KEY UPDATE `FDate`=VALUES(`FDate`), `FQty`=VALUES(`FQty`)"), sql);
        assertFalse(sql.contains("`FBillNo`=VALUES(`FBillNo`)"), "主键列不应出现在更新列表");
    }

    @Test
    @DisplayName("UPSERT：多主键列都从更新列表中排除")
    void upsertMultiKeys() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate", "FQty"), stageIndex(), List.of("FBillNo", "FDate"));

        assertTrue(sql.endsWith("ON DUPLICATE KEY UPDATE `FQty`=VALUES(`FQty`)"), sql);
    }

    @Test
    @DisplayName("UPSERT：目标表没有主键时全部列进更新列表（调用方会在日志里提示退化）")
    void upsertWithoutKeyColumns() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate"), stageIndex(), List.of());

        assertTrue(sql.endsWith(
                "ON DUPLICATE KEY UPDATE `FBillNo`=VALUES(`FBillNo`), `FDate`=VALUES(`FDate`)"), sql);
    }

    @Test
    @DisplayName("UPSERT：全部列都是主键时用自赋值占位，避免空 UPDATE 子句")
    void upsertAllKeyColumns() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex(), List.of("FBillNo"));

        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE `FBillNo`=VALUES(`FBillNo`)"), sql);
    }

    @Test
    @DisplayName("列名大小写不一致也能匹配，SELECT 用临时表实际列名")
    void caseInsensitiveMatching() {
        Map<String, String> index = new LinkedHashMap<>();
        index.put("fbillno", "fbillno");
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.APPEND),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), index, List.of());

        assertEquals("INSERT INTO `dwd_order` (`FBillNo`)\n"
                + "SELECT `fbillno` FROM `stg_pl_1001`", sql);
    }

    @Test
    @DisplayName("表名支持 db.table，并转义反引号")
    void tableRefAndEscaping() {
        assertEquals("`dwd_order`", AutoInsertSqlBuilder.tableRef("dwd_order"));
        assertEquals("`dw`.`dwd_order`", AutoInsertSqlBuilder.tableRef("dw.dwd_order"));
        assertEquals("a``b", AutoInsertSqlBuilder.escape("a`b"));

        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.APPEND),
                "dw.dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex(), List.of());
        assertTrue(sql.contains("INSERT INTO `dw`.`dwd_order`"), sql);
    }
}
