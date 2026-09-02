package com.wuxibio.care.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SensitiveDataCryptoService {

    private static final String PREFIX = "ENC::";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public SensitiveDataCryptoService(
            @Value("${app.security.data-key:${app.jwt.secret}}")
            String rawKey) {
        this.keySpec = new SecretKeySpec(hashToAesKey(rawKey), "AES");
    }

    public String encryptIfNeeded(String plainText) {
        if (plainText == null || plainText.isBlank()) return plainText;
        if (isEncrypted(plainText)) return plainText;
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("加密敏感信息失败", e);
        }
    }

    public String decryptIfNeeded(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return cipherText;
        if (!isEncrypted(cipherText)) return cipherText;
        try {
            String encoded = cipherText.substring(PREFIX.length());
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= GCM_NONCE_BYTES) {
                throw new IllegalStateException("密文格式非法");
            }

            byte[] nonce = new byte[GCM_NONCE_BYTES];
            byte[] encrypted = new byte[payload.length - GCM_NONCE_BYTES];
            System.arraycopy(payload, 0, nonce, 0, GCM_NONCE_BYTES);
            System.arraycopy(payload, GCM_NONCE_BYTES, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密敏感信息失败", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private byte[] hashToAesKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((rawKey == null ? "" : rawKey).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }
}
