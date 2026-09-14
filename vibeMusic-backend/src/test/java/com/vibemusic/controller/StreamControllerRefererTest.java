package com.vibemusic.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamController 代理头纯单元测试（不启动 Spring）。
 */
@DisplayName("StreamController B站 Referer 单元测试")
class StreamControllerRefererTest {

    @Test
    @DisplayName("bilivideo 子域目标带 Referer")
    void shouldAddRefererForBilivideoHost() {
        HttpHeaders headers = new HttpHeaders();
        StreamController.applyProxyHeaders(headers, "upos-sz-mirrorcos.bilivideo.com", "bytes=0-1023");

        assertEquals("https://www.bilibili.com/", headers.getFirst("Referer"));
        assertEquals("bytes=0-1023", headers.getFirst("Range"));
        assertNotNull(headers.getFirst("User-Agent"));
    }

    @Test
    @DisplayName("hdslb 子域目标带 Referer")
    void shouldAddRefererForHdslbHost() {
        HttpHeaders headers = new HttpHeaders();
        StreamController.applyProxyHeaders(headers, "cn-hb-ok-01-05.hdslb.com", null);

        assertEquals("https://www.bilibili.com/", headers.getFirst("Referer"));
        assertNotNull(headers.getFirst("User-Agent"));
        assertNull(headers.getFirst("Range"));
    }

    @Test
    @DisplayName("非 B 站目标不带 Referer")
    void shouldNotAddRefererForOtherHosts() {
        for (String host : new String[]{"p1.music.126.net", "y.gtimg.cn", "stream.qqmusic.qq.com", null}) {
            HttpHeaders headers = new HttpHeaders();
            StreamController.applyProxyHeaders(headers, host, null);

            assertNull(headers.getFirst("Referer"), "host=" + host + " 不应带 Referer");
            assertNotNull(headers.getFirst("User-Agent"));
        }
    }

    @Test
    @DisplayName("伪造后缀不带 Referer")
    void shouldNotAddRefererForSpoofedHost() {
        assertFalse(StreamController.isBilibiliCdnHost("bilivideo.com.evil.com"));
        assertFalse(StreamController.isBilibiliCdnHost("evilbilivideo.com"));
        assertTrue(StreamController.isBilibiliCdnHost("bilivideo.com"));
        assertTrue(StreamController.isBilibiliCdnHost("UPOS-SZ.bilivideo.COM"));
    }
}
