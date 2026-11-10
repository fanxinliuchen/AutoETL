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
 * 金蝶报表取数解析测试。
 *
 * <p>报表接口返回结构：{@code {success, message, CurQueryId, data, TotalCount}}。
 * FieldKeys 留空时返回方案全部列，行对象的 key 即字段标识。
 */
class KingdeeReportClientTest {

    private final KingdeeReportClient client = new KingdeeReportClient(null);

    @Test
    @DisplayName("有数据：按首行 key 推断字段，并带出类型")
    void parseFieldsFromFirstRow() {
        String json = """
                {
                  "success": true,
                  "message": "查询成功",
                  "CurQueryId": "",
                  "TotalCount": 3,
                  "data": [
                    { "FStockId": 1001, "FStockId.FNumber": "CK001", "FBaseQty": 12.5,
                      "FBeginDate": "2026-11-01T00:00:00", "FDocumentStatus": "C" }
                  ]
                }
                """;

        Map<String, Object> result = client.parseFields(json);

        assertEquals(5, result.get("fieldCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        assertEquals("FStockId", fields.get(0).get("key"));
        assertEquals("FStockId", fields.get(0).get("label"), "报表行没有中文名，先用标识占位");
        assertEquals("BIGINT", fields.get(0).get("type"));
        assertEquals("VARCHAR(255)", fields.get(1).get("type"));
        assertEquals("DECIMAL(18,4)", fields.get(2).get("type"));
        assertEquals("DATETIME", fields.get(3).get("type"));
    }

    @Test
    @DisplayName("无数据：明确提示推不出字段，而不是报错")
    void emptyData() {
        Map<String, Object> result = client.parseFields(
                "{\"success\":true,\"message\":\"查询成功\",\"CurQueryId\":\"\",\"data\":null,\"TotalCount\":0}");

        assertEquals(0, result.get("fieldCount"));
        assertEquals(0, result.get("totalCount"));
        assertTrue(String.valueOf(result.get("message")).contains("没有返回数据"), String.valueOf(result.get("message")));
    }

    @Test
    @DisplayName("业务失败：透出金蝶原文（如过滤方案不存在）")
    void businessFailure() {
        BizException ex = assertThrows(BizException.class, () -> client.parseFields(
                "{\"success\":false,\"message\":\"过滤方案FSCHEMEID不正确\",\"data\":null,\"TotalCount\":0}"));
        assertTrue(ex.getMessage().contains("FSCHEMEID"), ex.getMessage());
    }

    @Test
    @DisplayName("接口地址写错时返回纯文本，提示检查地址")
    void nonJsonResponse() {
        BizException ex = assertThrows(BizException.class,
                () -> client.parseFields("response_error: kingdee ... no found."));
        assertTrue(ex.getMessage().contains("接口返回异常"), ex.getMessage());
    }
}
