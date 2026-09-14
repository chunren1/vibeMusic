package com.vibemusic.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnonymousIdentityUtils 标识解析与清洗测试
 */
@DisplayName("AnonymousIdentityUtils 匿名标识测试")
class AnonymousIdentityUtilsTest {

    @Test
    @DisplayName("deviceId 参数应优先于 X-Device-Id 请求头")
    void shouldPreferParamOverHeader() {
        assertEquals("param-device",
                AnonymousIdentityUtils.resolveAnonymousId("param-device", "header-device"));
    }

    @Test
    @DisplayName("无参数时应使用请求头")
    void shouldUseHeaderWhenParamAbsent() {
        assertEquals("header-device",
                AnonymousIdentityUtils.resolveAnonymousId(null, "header-device"));
    }

    @Test
    @DisplayName("两者都缺失时应回退到 anon")
    void shouldFallbackWhenBothAbsent() {
        assertEquals("anon", AnonymousIdentityUtils.resolveAnonymousId(null, null));
        assertEquals("anon", AnonymousIdentityUtils.resolveAnonymousId("   ", "  "));
    }

    @Test
    @DisplayName("非法字符应被剔除")
    void shouldStripIllegalChars() {
        assertEquals("abc-12_X", AnonymousIdentityUtils.sanitize("  abc-12_X!@#: \n"));
        assertEquals("anon", AnonymousIdentityUtils.sanitize("!!!"));
    }

    @Test
    @DisplayName("超长标识应截断至 64 字符")
    void shouldTruncateLongId() {
        String longId = "d".repeat(100);
        String result = AnonymousIdentityUtils.sanitize(longId);
        assertEquals(64, result.length());
        assertEquals("d".repeat(64), result);
    }
}
