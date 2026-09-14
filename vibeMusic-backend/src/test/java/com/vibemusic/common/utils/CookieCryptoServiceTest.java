package com.vibemusic.common.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CookieCryptoService 单元测试
 * <p>
 * 纯单元测试，不依赖 Spring 上下文，直接实例化 CookieCryptoService。
 * 覆盖：往返加解密、错误密钥、密文篡改、空白输入拒绝、IV 唯一性、密钥缺失 fail-fast。
 */
@DisplayName("CookieCryptoService Cookie 加解密测试")
class CookieCryptoServiceTest {

    private CookieCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CookieCryptoService(newKey());
    }

    /** 生成随机 32 字节 base64 密钥（AES-256）。 */
    private static String newKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Nested
    @DisplayName("encrypt/decrypt 往返加解密")
    class RoundTripTest {

        @Test
        @DisplayName("加密后再解密应还原原文（含中文与特殊字符）")
        void shouldRoundTrip() {
            String plaintext = "user=13800138000; vip=true; token=abc:def/中文🎵";
            String blob = cryptoService.encrypt(plaintext);

            assertNotNull(blob);
            assertTrue(blob.contains(":"), "密文应为 ivB64:ctB64 格式");
            assertEquals(plaintext, cryptoService.decrypt(blob));
        }

        @Test
        @DisplayName("长 Cookie 明文应正确往返")
        void shouldRoundTripLongPlaintext() {
            String plaintext = "MUSIC_U=abc123; ".repeat(50);
            assertEquals(plaintext, cryptoService.decrypt(cryptoService.encrypt(plaintext)));
        }
    }

    @Nested
    @DisplayName("错误密钥解密")
    class WrongKeyTest {

        @Test
        @DisplayName("用不同密钥解密应失败")
        void shouldFailWithWrongKey() {
            String blob = cryptoService.encrypt("敏感cookie值");
            CookieCryptoService otherKeyService = new CookieCryptoService(newKey());

            assertThrows(IllegalArgumentException.class, () -> otherKeyService.decrypt(blob));
        }
    }

    @Nested
    @DisplayName("密文篡改")
    class TamperTest {

        @Test
        @DisplayName("篡改密文体后解密应失败")
        void shouldFailWhenCiphertextTampered() {
            String blob = cryptoService.encrypt("敏感cookie值需要被保护起来才行");
            int sep = blob.indexOf(':');
            String ctPart = blob.substring(sep + 1);
            char original = ctPart.charAt(2);
            char replacement = original == 'A' ? 'B' : 'A';
            String tampered = blob.substring(0, sep + 1) + ctPart.substring(0, 2) + replacement + ctPart.substring(3);

            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt(tampered));
        }

        @Test
        @DisplayName("格式非法（缺分隔符/多分隔符/乱码）应失败")
        void shouldFailForMalformedBlob() {
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt("no-separator-here"));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt("a:b:c"));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt(":onlycipher"));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt("aW52YWxpZA==:%%%-not-base64-%%%"));
        }
    }

    @Nested
    @DisplayName("空白输入拒绝")
    class BlankInputTest {

        @Test
        @DisplayName("加密空输入应拒绝")
        void shouldRejectBlankEncryptInput() {
            assertThrows(IllegalArgumentException.class, () -> cryptoService.encrypt(null));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.encrypt(""));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.encrypt("   "));
        }

        @Test
        @DisplayName("解密空输入应拒绝")
        void shouldRejectBlankDecryptInput() {
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt(null));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt(""));
            assertThrows(IllegalArgumentException.class, () -> cryptoService.decrypt("   "));
        }
    }

    @Nested
    @DisplayName("IV 唯一性")
    class IvUniquenessTest {

        @Test
        @DisplayName("同一明文两次加密结果应不同，且均可解密")
        void shouldUseFreshIvPerEncryption() {
            String plaintext = "同一明文";
            String blob1 = cryptoService.encrypt(plaintext);
            String blob2 = cryptoService.encrypt(plaintext);

            assertNotEquals(blob1, blob2, "每次加密必须使用全新随机 IV");
            assertEquals(plaintext, cryptoService.decrypt(blob1));
            assertEquals(plaintext, cryptoService.decrypt(blob2));
        }
    }

    @Nested
    @DisplayName("密钥缺失 fail-fast")
    class MissingKeyTest {

        @Test
        @DisplayName("缺失/非法密钥构造时应直接失败，不使用兜底默认值")
        void shouldFailFastOnMissingOrInvalidKey() {
            assertThrows(IllegalStateException.class, () -> new CookieCryptoService(null));
            assertThrows(IllegalStateException.class, () -> new CookieCryptoService(""));
            assertThrows(IllegalStateException.class, () -> new CookieCryptoService("   "));
            assertThrows(IllegalStateException.class, () -> new CookieCryptoService("!!!not-base64!!!"));
            // 16 字节密钥（长度不足 32 字节）也应拒绝
            byte[] shortRaw = new byte[16];
            new SecureRandom().nextBytes(shortRaw);
            assertThrows(IllegalStateException.class,
                    () -> new CookieCryptoService(Base64.getEncoder().encodeToString(shortRaw)));
        }
    }
}
