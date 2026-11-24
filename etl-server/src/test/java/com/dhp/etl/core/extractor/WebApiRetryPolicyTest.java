package com.dhp.etl.core.extractor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebAPI 重试策略测试。
 *
 * <p>回归背景：2026-11-20 08:00 应收单在第 113 页深偏移处读超时，
 * 30 秒硬超时 + 无重试导致 22.4 万行作废。这里固化「哪些错误该重试」的口径：
 * 网络超时/连接重置/5xx/429 重试，业务错误（字段不存在、参数非法）不重试。
 */
class WebApiRetryPolicyTest {

    @Test
    @DisplayName("网络类异常可重试，且能穿透 Hutool 的包装链")
    void networkErrorsAreRetryable() {
        assertTrue(WebApiExtractor.isRetryableNetworkError(new SocketTimeoutException("Read timed out")));
        assertTrue(WebApiExtractor.isRetryableNetworkError(new ConnectException("Connection refused")));
        // Hutool 会把 IOException 包成 IORuntimeException，真正的类型在 cause 里
        RuntimeException wrapped = new RuntimeException("request error",
                new RuntimeException(new SocketTimeoutException("Read timed out")));
        assertTrue(WebApiExtractor.isRetryableNetworkError(wrapped));
        assertTrue(WebApiExtractor.isRetryableNetworkError(new IOException("Connection reset")));
    }

    @Test
    @DisplayName("业务/参数类异常不可重试，避免把参数问题放大成多次无效请求")
    void businessErrorsAreNotRetryable() {
        assertFalse(WebApiExtractor.isRetryableNetworkError(
                new IllegalArgumentException("存在未定义的变量: begindate")));
        assertFalse(WebApiExtractor.isRetryableNetworkError(
                new RuntimeException("金蝶返回业务错误: 字段不存在")));
        assertFalse(WebApiExtractor.isRetryableNetworkError(null));
    }

    @Test
    @DisplayName("HTTP 5xx / 429 重试，4xx 不重试")
    void httpStatusPolicy() {
        assertTrue(WebApiExtractor.isRetryableHttpStatus(500));
        assertTrue(WebApiExtractor.isRetryableHttpStatus(502));
        assertTrue(WebApiExtractor.isRetryableHttpStatus(503));
        assertTrue(WebApiExtractor.isRetryableHttpStatus(429));
        assertFalse(WebApiExtractor.isRetryableHttpStatus(400));
        assertFalse(WebApiExtractor.isRetryableHttpStatus(401));
        assertFalse(WebApiExtractor.isRetryableHttpStatus(404));
    }
}
