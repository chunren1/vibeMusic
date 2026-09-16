package com.vibemusic.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CoverUrlUtils 封面 URL 净化测试
 */
@DisplayName("CoverUrlUtils 封面 URL 净化测试")
class CoverUrlUtilsTest {

    @Test
    @DisplayName("null / 空白 / 字面量 null 一律净化成空串")
    void shouldCleanNullLikeValues() {
        assertEquals("", CoverUrlUtils.cleanCoverUrl(null));
        assertEquals("", CoverUrlUtils.cleanCoverUrl(""));
        assertEquals("", CoverUrlUtils.cleanCoverUrl("   "));
        assertEquals("", CoverUrlUtils.cleanCoverUrl("null"));
        assertEquals("", CoverUrlUtils.cleanCoverUrl("NULL"));
        assertEquals("", CoverUrlUtils.cleanCoverUrl(" null "));
    }

    @Test
    @DisplayName("http 升级为 https，https 原样保留")
    void shouldUpgradeHttpToHttps() {
        assertEquals("https://cdn/x.jpg", CoverUrlUtils.cleanCoverUrl("http://cdn/x.jpg"));
        assertEquals("https://cdn/x.jpg", CoverUrlUtils.cleanCoverUrl("https://cdn/x.jpg"));
    }

    @Test
    @DisplayName("相对路径（image-proxy 形态）原样保留由客户端拼域名")
    void shouldKeepRelativePath() {
        assertEquals("/api/image-proxy?url=x",
                CoverUrlUtils.cleanCoverUrl("/api/image-proxy?url=x"));
    }

    @Test
    @DisplayName("非字符串类型按字符串处理")
    void shouldHandleNonStringValues() {
        assertEquals("12345", CoverUrlUtils.cleanCoverUrl(12345));
    }
}
