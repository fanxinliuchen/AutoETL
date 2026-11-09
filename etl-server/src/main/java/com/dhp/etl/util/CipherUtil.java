package com.dhp.etl.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 密码对称加解密工具类 (规范 03 §10)
 */
@Component
public class CipherUtil {

    private final AES aes;

    public CipherUtil(@Value("${etl.security.cipher-key:DhpEtlSecretKey2026!@#}") String key) {
        byte[] keyBytes = SecureUtil.sha256(key).substring(0, 16).getBytes(StandardCharsets.UTF_8);
        this.aes = SecureUtil.aes(keyBytes);
    }

    /**
     * 加密明文密码
     */
    public String encrypt(String plainText) {
        if (StrUtil.isBlank(plainText)) {
            return plainText;
        }
        return aes.encryptHex(plainText);
    }

    /**
     * 解密密文密码
     */
    public String decrypt(String cipherText) {
        if (StrUtil.isBlank(cipherText)) {
            return cipherText;
        }
        try {
            return aes.decryptStr(cipherText);
        } catch (Exception e) {
            // 兼容可能未加密的旧密码
            return cipherText;
        }
    }
}
