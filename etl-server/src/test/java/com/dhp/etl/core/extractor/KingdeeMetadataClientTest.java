package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金蝶元数据解析器单元测试。
 *
 * <p>各版本 QueryBusinessInfo 返回结构存在差异，这里用几种典型形态固化解析行为，
 * 避免后续改动破坏容错能力。
 */
class KingdeeMetadataClientTest {

    private final KingdeeMetadataClient client = new KingdeeMetadataClient(null);

    @Test
    @DisplayName("标准 Result.NeedReturnData 结构：解析出字段名/中文名/类型")
    void parseStandardStructure() {
        String json = """
                {
                  "Result": {
                    "ResponseStatus": { "IsSuccess": true },
                    "NeedReturnData": [
                      { "Key": "FID", "Caption": "单据内码", "FieldType": "Integer" },
                      { "Key": "FBillNo", "Caption": "单据编号", "FieldType": "Text" },
                      { "Key": "FDate", "Caption": "单据日期", "FieldType": "DateTime" },
                      { "Key": "FQty", "Caption": "数量", "FieldType": "Decimal" }
                    ]
                  }
                }
                """;

        List<Map<String, Object>> fields = client.parseFields(json, "PUR_PurchaseOrder");

        assertEquals(4, fields.size());
        assertEquals("FID", fields.get(0).get("key"));
        assertEquals("单据内码", fields.get(0).get("label"));
        assertEquals("BIGINT", fields.get(0).get("type"));
        assertEquals("VARCHAR(255)", fields.get(1).get("type"));
        assertEquals("DATETIME", fields.get(2).get("type"));
        assertEquals("DECIMAL(18,4)", fields.get(3).get("type"));
    }

    @Test
    @DisplayName("字段数组挂在其它层级时仍能递归找到")
    void parseNestedStructure() {
        String json = """
                {
                  "Result": {
                    "ResponseStatus": { "IsSuccess": true },
                    "Data": {
                      "Entity": {
                        "Fields": [
                          { "FieldKey": "FMaterialId", "Name": "物料", "DataType": "String" },
                          { "FieldKey": "FAmount", "Name": "金额", "DataType": "Decimal" }
                        ]
                      }
                    }
                  }
                }
                """;

        List<Map<String, Object>> fields = client.parseFields(json, "BD_MATERIAL");

        assertEquals(2, fields.size());
        assertEquals("FMaterialId", fields.get(0).get("key"));
        assertEquals("物料", fields.get(0).get("label"));
        assertEquals("DECIMAL(18,4)", fields.get(1).get("type"));
    }

    @Test
    @DisplayName("同名字段去重，保留首次出现")
    void dedupeByKey() {
        String json = """
                {
                  "NeedReturnData": [
                    { "Key": "FID", "Caption": "单据内码", "FieldType": "Integer" },
                    { "Key": "fid", "Caption": "重复项", "FieldType": "Text" }
                  ]
                }
                """;

        List<Map<String, Object>> fields = client.parseFields(json, "PUR_PurchaseOrder");

        assertEquals(1, fields.size());
        assertEquals("单据内码", fields.get(0).get("label"));
    }

    @Test
    @DisplayName("业务失败时抛出可读错误")
    void raiseBusinessError() {
        String json = """
                {
                  "Result": {
                    "ResponseStatus": {
                      "IsSuccess": false,
                      "Errors": [ { "Message": "表单 PUR_XXX 不存在" } ]
                    }
                  }
                }
                """;

        BizException ex = assertThrows(BizException.class, () -> client.parseFields(json, "PUR_XXX"));
        assertTrue(ex.getMessage().contains("表单 PUR_XXX 不存在"), ex.getMessage());
    }

    @Test
    @DisplayName("完全识别不出字段时给出可排查的错误")
    void raiseWhenNothingRecognized() {
        BizException ex = assertThrows(BizException.class,
                () -> client.parseFields("{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true}}}", "PUR_XXX"));
        assertTrue(ex.getMessage().contains("未能从返回结果中识别出字段定义"), ex.getMessage());
    }

    @Test
    @DisplayName("非法 JSON 不抛原始异常，转为业务异常")
    void raiseOnInvalidJson() {
        BizException ex = assertThrows(BizException.class, () -> client.parseFields("<html>502</html>", "PUR_XXX"));
        assertTrue(ex.getMessage().contains("不是合法 JSON"), ex.getMessage());
    }

    @Test
    @DisplayName("金蝶类型映射：未知类型回退到按字段名推断")
    void mapUnknownTypeFallsBackToKey() {
        assertEquals("DECIMAL(18,4)", client.mapKingdeeType("SomeWeirdType", "FAmount"));
        assertEquals("DATETIME", client.mapKingdeeType("", "FCreateTime"));
        assertEquals("BIGINT", client.mapKingdeeType(null, "FSupplierId"));
    }

    @Test
    @DisplayName("按字段名推断类型的口径")
    void inferByKey() {
        assertEquals("BIGINT", KingdeeMetadataClient.inferTypeByKey("FID"));
        assertEquals("BIGINT", KingdeeMetadataClient.inferTypeByKey("FSupplierId"));
        assertEquals("DATE", KingdeeMetadataClient.inferTypeByKey("FDate"));
        assertEquals("DATETIME", KingdeeMetadataClient.inferTypeByKey("FApproveTime"));
        assertEquals("DECIMAL(18,4)", KingdeeMetadataClient.inferTypeByKey("FBillAllAmount"));
        assertEquals("INT", KingdeeMetadataClient.inferTypeByKey("FSeq"));
        assertEquals("VARCHAR(32)", KingdeeMetadataClient.inferTypeByKey("FDocumentStatus"));
        assertEquals("VARCHAR(255)", KingdeeMetadataClient.inferTypeByKey("FBillNo"));
        assertEquals("TEXT", KingdeeMetadataClient.inferTypeByKey(""));
    }
}
