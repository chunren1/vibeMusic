package com.vibemusic.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cookie 加解密服务（AES-256-GCM，JDK javax.crypto 原生实现，零新增依赖）
 *
 * <p>密钥：{@code COOKIE_ENC_KEY} 环境变量（32 字节 base64 编码），经
 * {@code @Value} 占位符注入（与 {@code JwtUtils} 的 {@code ${jwt.secret}} 同风格）；
 * 缺失或非法时启动即 fail-fast，绝不使用兜底默认值。
 *
 * <p>密文格式：{@code "ivB64:ctB64"}（每次加密随机 IV，GCM 自带完整性校验，
 * 篡改/密钥错误时解密直接失败）。
 *
 * <p>日志红线：绝不记录明文与密文，仅记录密钥缺失/配置错误（不含任何密钥值）。
 */
@Slf4j
@Service
public class CookieCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CookieCryptoService(@Value("${COOKIE_ENC_KEY:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.error("Cookie 加解密密钥缺失：请设置 COOKIE_ENC_KEY 环境变量");
            throw new IllegalStateException("COOKIE_ENC_KEY 未配置：请设置 COOKIE_ENC_KEY 环境变量（32 字节 base64 编码密钥）");
        }
        final byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            log.error("Cookie 加解密密钥无效：COOKIE_ENC_KEY 不是合法 base64 编码");
            throw new IllegalStateException("COOKIE_ENC_KEY 配置无效：不是合法的 base64 编码", e);
        }
        try {
            if (raw.length != KEY_BYTES) {
                log.error("Cookie 加解密密钥长度非法：解码后必须为 32 字节");
                throw new IllegalStateException(
                        "COOKIE_ENC_KEY 配置无效：解码后必须为 32 字节（AES-256），当前为 " + raw.length + " 字节");
            }
            this.secretKey = new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /**
     * 加密明文，返回 {@code "ivB64:ctB64"}（每次调用生成全新随机 IV）。
     *
     * @param plaintext 待加密明文（非空）
     * @return {@code ivB64:ctB64} 格式密文
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("待加密内容不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cookie 加密失败", e);
        }
    }

    /**
     * 解密 {@code "ivB64:ctB64"} 格式密文，还原明文。
     *
     * @param blob {@code ivB64:ctB64} 格式密文（非空）
     * @return 原始明文
     * @throws IllegalArgumentException 格式非法、密文被篡改或密钥不匹配时抛出
     */
    public String decrypt(String blob) {
        if (blob == null || blob.isBlank()) {
            throw new IllegalArgumentException("待解密内容不能为空");
        }
        int sep = blob.indexOf(':');
        if (sep <= 0 || sep == blob.length() - 1 || blob.indexOf(':', sep + 1) >= 0) {
            throw new IllegalArgumentException("密文格式无效");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(blob.substring(0, sep));
            byte[] ct = Base64.getDecoder().decode(blob.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // GCM 认证失败（密钥错误/密文篡改）与 base64 非法统一落到此处，不回显任何密文内容
            throw new IllegalArgumentException("Cookie 解密失败：密文无效或密钥不匹配", e);
        }
    }
}
