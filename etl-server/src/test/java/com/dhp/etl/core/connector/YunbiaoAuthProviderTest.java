package com.dhp.etl.core.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云表鉴权：签名与登录返回解析
 */
class YunbiaoAuthProviderTest {

    @Test
    @DisplayName("签名用时间戳在前：与官方文档示例逐位一致")
    void signMatchesDocVector() {
        // 文档示例：AppKey=058457b7-0e4a-4f6c-b7f4-10d56689f036, ts=1389085779854
        // 期望 F3DFAE8C1B952779ED068B9DB66BE258 —— 正文写「AppKey+时间戳」是错的，实际时间戳在前
        assertEquals("F3DFAE8C1B952779ED068B9DB66BE258",
                YunbiaoAuthProvider.sign("058457b7-0e4a-4f6c-b7f4-10d56689f036", 1389085779854L));
    }

    @Test
    @DisplayName("签名头是 sign,timestamp 两段")
    void signHeaderFormat() {
        String header = YunbiaoAuthProvider.signHeader("abc");
        String[] parts = header.split(",");
        assertEquals(2, parts.length);
        assertEquals(32, parts[0].length(), "MD5 32 位大写");
        assertEquals(parts[0].toUpperCase(), parts[0]);
        assertTrue(Long.parseLong(parts[1]) > 1_600_000_000_000L);
    }

    @Test
    @DisplayName("token 在根节点；兼容 Result 包裹")
    void extractToken() {
        assertEquals("4a623f01-269f-4035-89cc-04e016a36106",
                YunbiaoAuthProvider.extractToken(
                        "{\"logout\":\"/logout\",\"token\":\"4a623f01-269f-4035-89cc-04e016a36106\",\"user\":\"lakery\"}"));
        assertEquals("abc", YunbiaoAuthProvider.extractToken("{\"Result\":{\"token\":\"abc\"}}"));
        assertNull(YunbiaoAuthProvider.extractToken("{\"user\":\"x\"}"));
        assertNull(YunbiaoAuthProvider.extractToken(""));
    }
}
