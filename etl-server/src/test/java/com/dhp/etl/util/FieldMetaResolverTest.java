package com.dhp.etl.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段清单：列名映射（含中文列名）与类型解析
 */
class FieldMetaResolverTest {

    private String meta(String... fieldsJson) {
        return "{\"fields\":[" + String.join(",", fieldsJson) + "]}";
    }

    private String field(String k, String type, String column, boolean enabled) {
        return "{\"k\":\"" + k + "\",\"label\":\"\",\"type\":\"" + type + "\",\"enabled\":" + enabled
                + ",\"remark\":\"\",\"column\":\"" + column + "\"}";
    }

    @Test
    @DisplayName("手工列名优先；未填的按字段标识换算")
    void columnMappingOverride() {
        String meta = meta(
                field("物料名称", "VARCHAR(255)", "物料名称", true),
                field("规格型号", "VARCHAR(255)", "spec_model", true),
                field("FSaleOrgId.FNumber", "VARCHAR(255)", "", true));
        Map<String, String> map = FieldMetaResolver.columnMapping(meta, List.of("物料名称", "规格型号", "FSaleOrgId.FNumber"));
        assertEquals("物料名称", map.get("物料名称"));
        assertEquals("spec_model", map.get("规格型号"));
        assertEquals("FSaleOrgId_FNumber", map.get("FSaleOrgId.FNumber"));
    }

    @Test
    @DisplayName("中文数据项名本身可以当列名，不再被换成下划线")
    void chineseColumnSurvives() {
        assertEquals("物料名称", ColumnNaming.toColumn("物料名称"));
        assertEquals("数量_合计", ColumnNaming.toColumn("数量 合计"));
        Map<String, String> map = FieldMetaResolver.columnMapping(
                meta(field("物料名称", "VARCHAR(255)", "", true)), List.of("物料名称"));
        assertEquals("物料名称", map.get("物料名称"));
    }

    @Test
    @DisplayName("非法列名直接报错，不放进 DDL")
    void invalidColumnRejected() {
        String meta = meta(field("物料名称", "VARCHAR(255)", "bad`name", true));
        assertThrows(RuntimeException.class,
                () -> FieldMetaResolver.columnMapping(meta, List.of("物料名称")));
    }

    @Test
    @DisplayName("停用字段不参与列名与类型")
    void disabledFieldIgnored() {
        String meta = meta(
                field("物料名称", "VARCHAR(255)", "", true),
                field("停用字段", "VARCHAR(255)", "", false));
        assertTrue(FieldMetaResolver.enabledKeys(meta).contains("物料名称"));
        assertEquals(1, FieldMetaResolver.enabledKeys(meta).size());
        assertEquals(1, FieldMetaResolver.parseColumnTypes(meta).size());
    }

    @Test
    @DisplayName("类型按列名对齐：手工列名也能拿到类型")
    void typeAlignedWithColumn() {
        Map<String, String> types = FieldMetaResolver.parseColumnTypes(
                meta(field("规格型号", "VARCHAR(32)", "spec_model", true)));
        assertEquals("VARCHAR(32)", types.get("spec_model"));
    }
}
