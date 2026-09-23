package com.vibemusic.common.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlowRequestLogFilter 判定逻辑纯函数测试（round6）。
 */
@DisplayName("慢请求判定（流式端点豁免 + 阈值边界）")
class SlowRequestLogFilterTest {

    @Test
    void streamingEndpointsNeverLog_evenWhenLong() {
        assertFalse(SlowRequestLogFilter.shouldLog("/api/songs/stream", 180_000L));
        assertFalse(SlowRequestLogFilter.shouldLog("/api/image-proxy", 9_000L));
        assertFalse(SlowRequestLogFilter.shouldLog("/api/assistant/stream", 60_000L));
    }

    @Test
    void thresholdBoundary() {
        assertTrue(SlowRequestLogFilter.shouldLog("/api/songs/search", 1_500L));
        assertFalse(SlowRequestLogFilter.shouldLog("/api/songs/search", 1_499L));
        assertFalse(SlowRequestLogFilter.shouldLog("/api/songs/search", 0L));
    }

    @Test
    void normalEndpointsLog() {
        assertTrue(SlowRequestLogFilter.shouldLog("/api/playlists", 2_000L));
        assertTrue(SlowRequestLogFilter.shouldLog("/api/assistant/chat", 30_000L));
    }
}
