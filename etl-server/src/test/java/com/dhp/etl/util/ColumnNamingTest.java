package com.dhp.etl.util;

import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.SourceTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金蝶字段标识 -> 数仓列名换算测试。
 *
 * <p>金蝶 FieldKeys 允许带点取基础资料属性（FSaleOrgId.FNumber），
 * 发给金蝶必须是原名，落库必须换成合法列名，两者不能混用。
 */
class ColumnNamingTest {

    @Test
    @DisplayName("带点标识换算成下划线，普通标识保持不变")
    void toColumnName() {
        assertEquals("FSaleOrgId_FNumber", ColumnNaming.toColumn("FSaleOrgId.FNumber"));
        assertEquals("FBillNo", ColumnNaming.toColumn("FBillNo"));
        assertEquals("FEntity_FENTRYID", ColumnNaming.toColumn("FEntity_FENTRYID"));
        // 连续特殊字符合并成一个下划线；数组下标里的数字会保留
        assertEquals("A_B", ColumnNaming.toColumn("A..B"));
        assertEquals("A_0_B", ColumnNaming.toColumn("A[0].B"));
        // 数字开头补前缀，空值兜底
        assertEquals("c_1Number", ColumnNaming.toColumn("1Number"));
        assertEquals("", ColumnNaming.toColumn("   "));
        // 超长截断到 MySQL 列名上限
        assertEquals(64, ColumnNaming.toColumn("F" + "x".repeat(80)).length());
    }

    @Test
    @DisplayName("同批字段换算后重名时按顺序追加序号")
    void mappingKeepsOrderAndDedupes() {
        List<String> keys = List.of("FSaleOrgId.FNumber", "FSaleOrgId_FNumber", "FBillNo");
        Map<String, String> mapping = ColumnNaming.mapping(keys);
        assertEquals("FSaleOrgId_FNumber", mapping.get("FSaleOrgId.FNumber"));
        assertEquals("FSaleOrgId_FNumber_2", mapping.get("FSaleOrgId_FNumber"));
        assertEquals("FBillNo", mapping.get("FBillNo"));
        // 顺序与入参一致
        assertEquals(keys, List.copyOf(mapping.keySet()));
    }

    @Test
    @DisplayName("field_meta 的列类型按键为换算后的列名，带点字段不再退化成 VARCHAR(255)")
    void fieldMetaUsesConvertedColumn() {
        String meta = "{\"fields\":["
                + "{\"k\":\"FSaleOrgId.FNumber\",\"type\":\"VARCHAR(64)\",\"enabled\":true},"
                + "{\"k\":\"FDate\",\"type\":\"DATE\",\"enabled\":true},"
                + "{\"k\":\"FDisabled.Field\",\"type\":\"DATE\",\"enabled\":false}"
                + "]}";
        Map<String, String> types = FieldMetaResolver.parseColumnTypes(meta);
        assertEquals("VARCHAR(64)", types.get("FSaleOrgId_FNumber"));
        assertEquals("DATE", types.get("FDate"));
        assertTrue(!types.containsKey("FDisabled_Field"), "停用字段不该进建表类型");
        assertTrue(!types.containsKey("FSaleOrgId.FNumber"), "不应再用带点的原始标识做列名");
    }
}
