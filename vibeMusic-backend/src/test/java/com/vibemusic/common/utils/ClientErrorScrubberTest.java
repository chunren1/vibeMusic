package com.vibemusic.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("错误上报二次脱敏")
class ClientErrorScrubberTest {

    @Test
    @DisplayName("邮箱 / 手机号被替换")
    void scrubsEmailAndPhone() {
        String s = ClientErrorScrubber.scrub("用户 alice@example.com 手机 13812345678 报错");
        assertFalse(s.contains("alice@example.com"));
        assertFalse(s.contains("13812345678"));
        assertTrue(s.contains("<email>"));
        assertTrue(s.contains("<phone>"));
    }

    @Test
    @DisplayName("URL query 里的 token 被替换，键名保留")
    void scrubsQuerySecrets() {
        String s = ClientErrorScrubber.scrub("GET /api/x?token=abcdef123&page=2 失败");
        assertFalse(s.contains("abcdef123"));
        assertTrue(s.contains("token=<redacted>"));
        assertTrue(s.contains("page=2"));
    }

    @Test
    @DisplayName("Bearer 令牌与长十六进制串被替换")
    void scrubsBearerAndHex() {
        String s = ClientErrorScrubber.scrub(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc 与 key 9f8e7d6c5b4a39281706f5e4d3c2b1a0");
        assertFalse(s.contains("eyJhbGciOiJIUzI1NiJ9.abc"));
        assertFalse(s.contains("9f8e7d6c5b4a39281706f5e4d3c2b1a0"));
        assertTrue(s.contains("Bearer <redacted>"));
        assertTrue(s.contains("<hex>"));
    }

    @Test
    @DisplayName("换行等控制字符被压成空格，防日志注入/多行伪造")
    void stripsControlChars() {
        String s = ClientErrorScrubber.scrub("line1\nline2\r\n[CLIENT-ERROR] fake");
        assertFalse(s.contains("\n"));
        assertFalse(s.contains("\r"));
        assertTrue(s.contains("line1 line2"));
    }

    @Test
    @DisplayName("家目录用户名被抹掉")
    void scrubsHomeDir() {
        assertTrue(ClientErrorScrubber.scrub("/home/alice/project/a.js:1").contains("/home/<user>/"));
        assertTrue(ClientErrorScrubber.scrub("C:\\Users\\bob\\app.js").contains("\\<user>"));
    }

    @Test
    @DisplayName("路由只保留 pathname：query/hash/绝对 URL 都被裁掉")
    void pathOnlyStripsQueryAndOrigin() {
        assertEquals("/m/search", ClientErrorScrubber.pathOnly("/m/search?q=secret&token=abc"));
        assertEquals("/m/search", ClientErrorScrubber.pathOnly("/m/search#hash"));
        assertEquals("/api/songs/search", ClientErrorScrubber.pathOnly("https://vibe.cyk666.top/api/songs/search?x=1"));
        assertEquals("/", ClientErrorScrubber.pathOnly("https://vibe.cyk666.top"));
        assertEquals(null, ClientErrorScrubber.pathOnly(null));
    }

    @Test
    @DisplayName("null / 空串原样返回，不抛异常")
    void nullSafe() {
        assertEquals(null, ClientErrorScrubber.scrub(null));
        assertEquals("", ClientErrorScrubber.scrub(""));
    }

    @Test
    @DisplayName("CR/LF 与控制字符被替换，无法伪造日志行")
    void scrubsControlChars() {
        String s = ClientErrorScrubber.scrub("晴天\r\n2026-10-23 ERROR 伪造行\u0007");
        assertFalse(s.contains("\r"));
        assertFalse(s.contains("\n"));
        assertEquals(1, s.lines().count());
        assertTrue(s.contains("晴天"));
    }
}
