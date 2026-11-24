package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 云表 formData 报文：顶层字段即表单字段
 *
 * <p>云表 POST 只接受 formData。查询接口 {@code POST /{enpId}/openapi/1.0/{模板}} 要的是
 * {@code formJson}，值就是分页对象**本身**（不是再包一层 queryParams —— 实测包一层后
 * pageSize 会被忽略，服务端退回默认 50 条/页）。
 */
class YunbiaoPayloadTest {

    @Test
    @DisplayName("formJson 对象压成一行 JSON")
    void compactFormJson() {
        String body = """
                {
                  "formJson": {
                    "pageInfo": { "isUseRowIndex": true, "rowIndex": 0, "pageSize": 2000 },
                    "filter": [],
                    "paramList": []
                  }
                }
                """;
        Map<String, String> fields = WebApiExtractor.parseFormFields(body);
        assertEquals(1, fields.size());
        String formJson = fields.get("formJson");
        assertEquals("{\"pageInfo\":{\"isUseRowIndex\":true,\"rowIndex\":0,\"pageSize\":2000},\"filter\":[],\"paramList\":[]}",
                formJson);
        assertFalse(formJson.contains("\n"), "必须是压缩串，不能带换行");
    }

    @Test
    @DisplayName("formJson 已经是字符串时原样透传")
    void passthroughString() {
        Map<String, String> fields = WebApiExtractor.parseFormFields(
                "{\"formJson\":\"{\\\"pageInfo\\\":{}}\"}");
        assertEquals("{\"pageInfo\":{}}", fields.get("formJson"));
    }

    @Test
    @DisplayName("多个顶层字段各自成为一个表单字段")
    void multipleFields() {
        Map<String, String> fields = WebApiExtractor.parseFormFields(
                "{\"formJson\":{\"pageInfo\":{}},\"other\":\"x\"}");
        assertEquals(2, fields.size());
        assertEquals("{\"pageInfo\":{}}", fields.get("formJson"));
        assertEquals("x", fields.get("other"));
    }

    @Test
    @DisplayName("空报文或非 JSON 都要报清楚")
    void invalidBody() {
        assertThrows(BizException.class, () -> WebApiExtractor.parseFormFields("{}"));
        assertThrows(BizException.class, () -> WebApiExtractor.parseFormFields("not json"));
    }
}
