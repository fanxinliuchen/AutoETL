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
 * <p>真实返回结构已通过 PUR_PurchaseOrder 实测确认，核心差异：
 * Name 是 [{Key:2052,Value:"中文名"}] 数组、FieldType 是数字枚举、
 * Entry（单据体）节点也带 Key/Name 必须排除。
 */
class KingdeeMetadataClientTest {

    private final KingdeeMetadataClient client = new KingdeeMetadataClient(null);

    /** 真实报文结构（节选自 39.98.122.240 的 PUR_PurchaseOrder 返回） */
    private static final String REAL_SHAPE = """
            {
              "Result": {
                "ResponseStatus": { "IsSuccess": true, "Errors": [], "MsgCode": 0 },
                "NeedReturnData": {
                  "Id": "PUR_PurchaseOrder",
                  "Name": [ { "Key": 2052, "Value": "采购订单" } ],
                  "Entrys": [ {
                    "Id": "be8f270b",
                    "Key": "FBillHead",
                    "Name": [ { "Key": 2052, "Value": "基本信息" } ],
                    "EntryName": "POOrder",
                    "TableName": "t_PUR_POOrder",
                    "Fields": [
                      { "Key": "FBillNo", "Name": [ { "Key": 2052, "Value": "单据编号" } ],
                        "FieldName": "FBILLNO", "FieldType": 231, "Editlen": 50 },
                      { "Key": "FDocumentStatus", "Name": [ { "Key": 2052, "Value": "单据状态" } ],
                        "FieldName": "FDOCUMENTSTATUS", "FieldType": 175,
                        "Extends": [ { "Value": "A", "Caption": "创建" } ], "Editlen": 0 },
                      { "Key": "FSupplierId", "Name": [ { "Key": 2052, "Value": "供应商" } ],
                        "FieldName": "FSUPPLIERID", "FieldType": 100,
                        "LookUpObjectFormId": "BD_Supplier", "Editlen": 0 },
                      { "Key": "FDate", "Name": [ { "Key": 2052, "Value": "采购日期" } ],
                        "FieldName": "FDATE", "FieldType": 61, "Editlen": 0 },
                      { "Key": "FBillAllAmount", "Name": [ { "Key": 2052, "Value": "价税合计" } ],
                        "FieldName": "FBILLALLAMOUNT", "FieldType": 104, "Editlen": 0 }
                    ]
                  } ]
                }
              }
            }
            """;

    @Test
    @DisplayName("真实结构：中文名取自 Name 数组，单据体节点被排除")
    void parseRealKingdeeShape() {
        List<Map<String, Object>> fields = client.parseFields(REAL_SHAPE, "PUR_PurchaseOrder");

        assertEquals(5, fields.size(), "应只解析出 5 个字段，不能把 FBillHead / 表单本身算进来");
        assertEquals("FBillNo", fields.get(0).get("key"));
        assertEquals("单据编号", fields.get(0).get("label"), "中文名必须取自 Name[].Value");
        assertEquals("FSupplierId", fields.get(2).get("key"));
        assertEquals("供应商", fields.get(2).get("label"));
    }

    @Test
    @DisplayName("真实结构：枚举/基础资料/文本长度/金额 的类型判定")
    void resolveTypesFromRealMetadata() {
        List<Map<String, Object>> fields = client.parseFields(REAL_SHAPE, "PUR_PurchaseOrder");

        // 文本字段按 Editlen 取长度
        assertEquals("VARCHAR(50)", fields.get(0).get("type"));
        assertEquals("文本(50)", fields.get(0).get("rawType"));
        // 带 Extends 的是枚举，返回编码字符串
        assertEquals("VARCHAR(32)", fields.get(1).get("type"));
        assertEquals("枚举", fields.get(1).get("rawType"));
        // 基础资料裸字段返回内码
        assertEquals("BIGINT", fields.get(2).get("type"));
        assertEquals("基础资料", fields.get(2).get("rawType"));
        // 日期统一 DATETIME，避免 DATE 列静默丢掉时分秒
        assertEquals("DATETIME", fields.get(3).get("type"));
        // 金额走字段名推断
        assertEquals("DECIMAL(18,4)", fields.get(4).get("type"));
    }

    @Test
    @DisplayName("兼容：Caption + 字符串 FieldType 的扁平结构")
    void parseStandardStructure() {
        String json = """
                {
                  "Result": {
                    "ResponseStatus": { "IsSuccess": true },
                    "NeedReturnData": [
                      { "Key": "FID", "Caption": "单据内码", "FieldType": "Integer" },
                      { "Key": "FBillNo", "Caption": "单据编号", "FieldType": "Text" },
                      { "Key": "FQty", "Caption": "数量", "FieldType": "Decimal" }
                    ]
                  }
                }
                """;

        List<Map<String, Object>> fields = client.parseFields(json, "PUR_PurchaseOrder");

        assertEquals(3, fields.size());
        assertEquals("单据内码", fields.get(0).get("label"));
        assertEquals("BIGINT", fields.get(0).get("type"));
        assertEquals("VARCHAR(255)", fields.get(1).get("type"));
        assertEquals("DECIMAL(18,4)", fields.get(2).get("type"));
    }

    @Test
    @DisplayName("兼容：字段数组挂在其它的嵌套层级")
    void parseNestedStructure() {
        String json = """
                {
                  "Result": {
                    "ResponseStatus": { "IsSuccess": true },
                    "Data": { "Entity": { "Fields": [
                      { "FieldKey": "FMaterialId", "Name": "物料", "DataType": "String" },
                      { "FieldKey": "FAmount", "Name": "金额", "DataType": "Decimal" }
                    ] } }
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
                    { "Key": "FID", "Caption": "单据内码", "Editlen": 0 },
                    { "Key": "fid", "Caption": "重复项", "Editlen": 0 }
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
    @DisplayName("网关返回 HTML 时提示不是合法 JSON")
    void raiseOnHtmlResponse() {
        BizException ex = assertThrows(BizException.class, () -> client.parseFields("<html>502</html>", "PUR_XXX"));
        assertTrue(ex.getMessage().contains("不是合法 JSON"), ex.getMessage());
    }

    @Test
    @DisplayName("按字段名推断类型的口径（日期统一 DATETIME）")
    void inferByKey() {
        assertEquals("BIGINT", KingdeeMetadataClient.inferTypeByKey("FID"));
        assertEquals("BIGINT", KingdeeMetadataClient.inferTypeByKey("FSupplierId"));
        assertEquals("DATETIME", KingdeeMetadataClient.inferTypeByKey("FDate"));
        assertEquals("DATETIME", KingdeeMetadataClient.inferTypeByKey("FApproveTime"));
        assertEquals("DECIMAL(18,4)", KingdeeMetadataClient.inferTypeByKey("FBillAllAmount"));
        assertEquals("INT", KingdeeMetadataClient.inferTypeByKey("FSeq"));
        assertEquals("VARCHAR(32)", KingdeeMetadataClient.inferTypeByKey("FDocumentStatus"));
        assertEquals("VARCHAR(255)", KingdeeMetadataClient.inferTypeByKey("FBillNo"));
        // 布尔标志：FIsXxx 必须优先于金额规则，否则含 TAX 的布尔字段会被判成 DECIMAL
        assertEquals("VARCHAR(16)", KingdeeMetadataClient.inferTypeByKey("FIsIncludedTax"));
        assertEquals("VARCHAR(16)", KingdeeMetadataClient.inferTypeByKey("FISPRICEEXCLUDETAX"));
        assertEquals("DECIMAL(18,4)", KingdeeMetadataClient.inferTypeByKey("FBillTaxAmount"));
        assertEquals("TEXT", KingdeeMetadataClient.inferTypeByKey(""));
    }
}
