package com.dhp.etl.core.transformer;

import com.dhp.common.exception.BizException;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.WriteModeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 未配置转换 SQL 时的自动入库脚本生成
 */
class AutoInsertSqlBuilderTest {

    private EtlPipeline pipeline(WriteModeEnum mode, String upsertKeys) {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setTargetTable("dwd_order");
        pipeline.setWriteMode(mode);
        pipeline.setUpsertKeys(upsertKeys);
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
                pipeline(WriteModeEnum.APPEND, null),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate"), stageIndex());

        assertEquals("INSERT INTO `dwd_order` (`FBillNo`, `FDate`)\n"
                + "SELECT `FBillNo`, `FDate` FROM `stg_pl_1001`", sql);
        assertFalse(sql.contains("*"), "不应生成 SELECT *");
    }

    @Test
    @DisplayName("OVERWRITE：先 TRUNCATE 再插入")
    void overwriteMode() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.OVERWRITE, null),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex());

        assertTrue(sql.startsWith("TRUNCATE TABLE `dwd_order`;\n"), sql);
        assertTrue(sql.contains("INSERT INTO `dwd_order` (`FBillNo`)"), sql);
    }

    @Test
    @DisplayName("UPSERT：非主键列生成 ON DUPLICATE KEY UPDATE")
    void upsertMode() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT, "FBillNo"),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate", "FQty"), stageIndex());

        assertTrue(sql.endsWith(
                "ON DUPLICATE KEY UPDATE `FDate`=VALUES(`FDate`), `FQty`=VALUES(`FQty`)"), sql);
        assertFalse(sql.contains("`FBillNo`=VALUES(`FBillNo`)"), "主键列不应出现在更新列表");
    }

    @Test
    @DisplayName("UPSERT：多主键（逗号分隔、含空格）都能识别")
    void upsertMultiKeys() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT, " FBillNo , FDate "),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo", "FDate", "FQty"), stageIndex());

        assertTrue(sql.endsWith("ON DUPLICATE KEY UPDATE `FQty`=VALUES(`FQty`)"), sql);
    }

    @Test
    @DisplayName("UPSERT：未配置主键直接报错，不静默退化成追加")
    void upsertWithoutKeysFails() {
        BizException e = assertThrows(BizException.class, () -> AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT, "  "),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex()));
        assertTrue(e.getMessage().contains("未配置业务主键"), e.getMessage());
    }

    @Test
    @DisplayName("UPSERT：全部列都是主键时用自赋值占位，避免空 UPDATE 子句")
    void upsertAllKeyColumns() {
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.UPSERT, "FBillNo"),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex());

        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE `FBillNo`=VALUES(`FBillNo`)"), sql);
    }

    @Test
    @DisplayName("列名大小写不一致也能匹配，SELECT 用临时表实际列名")
    void caseInsensitiveMatching() {
        Map<String, String> index = new LinkedHashMap<>();
        index.put("fbillno", "fbillno");
        String sql = AutoInsertSqlBuilder.assemble(
                pipeline(WriteModeEnum.APPEND, null),
                "dwd_order", "stg_pl_1001",
                List.of("FBillNo"), index);

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
                pipeline(WriteModeEnum.APPEND, null),
                "dw.dwd_order", "stg_pl_1001",
                List.of("FBillNo"), stageIndex());
        assertTrue(sql.contains("INSERT INTO `dw`.`dwd_order`"), sql);
    }
}
