package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 云表地址拼装：用户只填表单名，{@code openapi/1.0/} 由后端补。
 */
class YunbiaoUrlTest {

    private static final String BASE = "http://yunbiao.whstby.cn/10001";

    @Test
    @DisplayName("只填表单名 → 自动补 openapi/1.0/")
    void formNameOnly() {
        assertEquals(BASE + "/openapi/1.0/表结合数采明细_IOT",
                WebApiExtractor.buildYunbiaoUrl(BASE, "表结合数采明细_IOT"));
        assertEquals(BASE + "/openapi/1.0/表结合数采明细_IOT",
                WebApiExtractor.buildYunbiaoUrl(BASE, "  表结合数采明细_IOT  "));
    }

    @Test
    @DisplayName("已带 openapi/ 前缀的相对路径 → 原样（兼容旧配置）")
    void relativePath() {
        assertEquals(BASE + "/openapi/1.0/表结合数采明细_IOT",
                WebApiExtractor.buildYunbiaoUrl(BASE, "openapi/1.0/表结合数采明细_IOT"));
        assertEquals(BASE + "/openapi/1.0/表结合数采明细_IOT",
                WebApiExtractor.buildYunbiaoUrl(BASE, "/openapi/1.0/表结合数采明细_IOT"));
    }

    @Test
    @DisplayName("完整 URL → 原样返回，不再拼服务地址")
    void absoluteUrl() {
        String full = "http://other.host/20002/openapi/1.0/xxx";
        assertEquals(full, WebApiExtractor.buildYunbiaoUrl(BASE, full));
        String https = "https://a.b/c";
        assertEquals(https, WebApiExtractor.buildYunbiaoUrl(BASE, https));
    }

    @Test
    @DisplayName("服务地址末尾带斜杠不会拼出 //")
    void baseWithTrailingSlash() {
        assertEquals(BASE + "/openapi/1.0/表单A",
                WebApiExtractor.buildYunbiaoUrl(BASE + "/", "表单A"));
    }

    @Test
    @DisplayName("表单名为空要报出来")
    void blankFormName() {
        assertThrows(BizException.class, () -> WebApiExtractor.buildYunbiaoUrl(BASE, ""));
        assertThrows(BizException.class, () -> WebApiExtractor.buildYunbiaoUrl(BASE, "   "));
    }
}
