package com.vibemusic.common.utils;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtils 单元测试
 * <p>
 * 纯单元测试，不依赖 Spring 上下文，直接实例化 JwtUtils。
 * 覆盖：生成 Token、解析用户 ID、校验有效性、过期/篡改场景。
 */
@DisplayName("JwtUtils JWT 工具类测试")
class JwtUtilsTest {

    private JwtUtils jwtUtils;
    private static final String SECRET = "test-secret-key-for-unit-testing-only-must-be-long-enough";
    private static final long EXPIRATION = 3600000L; // 1 小时

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(SECRET, EXPIRATION);
    }

    @Nested
    @DisplayName("generateToken 生成 Token")
    class GenerateTokenTest {

        @Test
        @DisplayName("应生成非空 JWT 字符串")
        void shouldGenerateNonEmptyToken() {
            String token = jwtUtils.generateToken("123");
            assertNotNull(token);
            assertFalse(token.isEmpty());
        }

        @Test
        @DisplayName("生成的 Token 应包含三段（header.payload.signature）")
        void shouldHaveThreeParts() {
            String token = jwtUtils.generateToken("123");
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length, "JWT 应由 3 段组成");
        }

        @Test
        @DisplayName("不同用户 ID 应生成不同 Token")
        void shouldGenerateDifferentTokensForDifferentUsers() {
            String token1 = jwtUtils.generateToken("123");
            String token2 = jwtUtils.generateToken("456");
            assertNotEquals(token1, token2);
        }
    }

    @Nested
    @DisplayName("getUserIdFromToken 解析用户 ID")
    class GetUserIdTest {

        @Test
        @DisplayName("应正确提取用户 ID")
        void shouldExtractUserId() {
            String token = jwtUtils.generateToken("12345");
            assertEquals("12345", jwtUtils.getUserIdFromToken(token));
        }

        @Test
        @DisplayName("长用户 ID 应正确提取")
        void shouldExtractLongUserId() {
            String token = jwtUtils.generateToken("9999999999");
            assertEquals("9999999999", jwtUtils.getUserIdFromToken(token));
        }
    }

    @Nested
    @DisplayName("validateToken 校验 Token")
    class ValidateTokenTest {

        @Test
        @DisplayName("有效 Token 应返回 true")
        void shouldReturnTrueForValidToken() {
            String token = jwtUtils.generateToken("123");
            assertTrue(jwtUtils.validateToken(token));
        }

        @Test
        @DisplayName("null Token 应返回 false")
        void shouldReturnFalseForNullToken() {
            assertFalse(jwtUtils.validateToken(null));
        }

        @Test
        @DisplayName("空字符串 Token 应返回 false")
        void shouldReturnFalseForEmptyToken() {
            assertFalse(jwtUtils.validateToken(""));
        }

        @Test
        @DisplayName("篡改后的 Token 应返回 false")
        void shouldReturnFalseForTamperedToken() {
            String token = jwtUtils.generateToken("123");
            // 篡改 payload 部分
            String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "tampered";
            assertFalse(jwtUtils.validateToken(tampered));
        }

        @Test
        @DisplayName("用不同密钥签名的 Token 应返回 false")
        void shouldReturnFalseForWrongSecretToken() {
            // 用另一个密钥生成 Token
            JwtUtils otherJwtUtils = new JwtUtils("another-secret-key-that-is-different-from-test", EXPIRATION);
            String token = otherJwtUtils.generateToken("123");
            assertFalse(jwtUtils.validateToken(token));
        }

        @Test
        @DisplayName("乱码字符串应返回 false")
        void shouldReturnFalseForGarbageString() {
            assertFalse(jwtUtils.validateToken("not.a.valid.jwt.token"));
        }

        @Test
        @DisplayName("过期的 Token 应返回 false")
        void shouldReturnFalseForExpiredToken() {
            // expiration 为负数，生成的 Token 立即过期
            JwtUtils expiredJwtUtils = new JwtUtils(SECRET, -1000L);
            String token = expiredJwtUtils.generateToken("123");
            assertFalse(jwtUtils.validateToken(token));
        }
    }

    @Nested
    @DisplayName("完整流程测试")
    class EndToEndTest {

        @Test
        @DisplayName("生成 → 校验 → 解析 完整流程")
        void shouldCompleteFullFlow() {
            String userId = "42";
            String token = jwtUtils.generateToken(userId);

            assertTrue(jwtUtils.validateToken(token));
            assertEquals(userId, jwtUtils.getUserIdFromToken(token));
        }
    }
}
