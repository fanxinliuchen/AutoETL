package com.dhp.etl.core.extractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页会话失效识别：只有明确要求重新登录的文案才触发重登续跑，
 * 业务错误（字段不存在、参数非法）不能误判，否则会无谓重试。
 */
class WebApiExtractorSessionTest {

    @Test
    @DisplayName("会话失效文案能识别")
    void sessionExpiredHints() {
        assertTrue(WebApiExtractor.isSessionExpired("金蝶返回业务错误: 会话信息已丢失，请重新登录"));
        assertTrue(WebApiExtractor.isSessionExpired("会话已过期"));
        assertTrue(WebApiExtractor.isSessionExpired("会话超时，请重新登入"));
        assertTrue(WebApiExtractor.isSessionExpired("登录已过期"));
        assertTrue(WebApiExtractor.isSessionExpired("token 过期"));
    }

    @Test
    @DisplayName("业务错误与空值不算会话失效")
    void businessErrorsNotSession() {
        assertFalse(WebApiExtractor.isSessionExpired("元数据中标识为 FCustMatlD 的字段不存在"));
        assertFalse(WebApiExtractor.isSessionExpired("FDate 格式不正确"));
        assertFalse(WebApiExtractor.isSessionExpired("分页第 1 页拉取失败"));
        assertFalse(WebApiExtractor.isSessionExpired(null));
        assertFalse(WebApiExtractor.isSessionExpired(""));
    }
}
