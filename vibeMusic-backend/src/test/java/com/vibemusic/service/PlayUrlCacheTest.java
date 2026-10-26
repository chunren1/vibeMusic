package com.vibemusic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlayUrlCache} 单测：键形状（跨用户隔离）、TTL 取舍、空值不缓存、作废路径。
 */
class PlayUrlCacheTest {

    private JsonCacheService cache;
    private PlayUrlCache playUrlCache;

    @BeforeEach
    void setUp() {
        cache = mock(JsonCacheService.class);
        playUrlCache = new PlayUrlCache(cache);
    }

    @Test
    @DisplayName("键包含调用者作用域：同一首歌不同用户不得共用（防 VIP 地址泄漏）")
    void keyIsScopedByUser() {
        String anon = PlayUrlCache.key(null, "netease", "186016");
        String userA = PlayUrlCache.key("7", "netease", "186016");
        String userB = PlayUrlCache.key("8", "netease", "186016");

        assertEquals("play:url:v1:anon:netease:186016", anon);
        assertEquals("play:url:v1:7:netease:186016", userA);
        assertTrue(!userA.equals(userB), "不同用户的键必须不同");
    }

    @Test
    @DisplayName("键对缺省平台用 auto，平台名统一小写（避免 NETEASE/netease 分裂成两条）")
    void keyNormalizesPlatform() {
        assertEquals("play:url:v1:anon:auto:186016", PlayUrlCache.key(null, null, "186016"));
        assertEquals("play:url:v1:anon:auto:186016", PlayUrlCache.key(null, "  ", "186016"));
        assertEquals(PlayUrlCache.key(null, "netease", "1"), PlayUrlCache.key(null, "NETEASE", "1"));
    }

    @Test
    @DisplayName("未命中返回 null 且计 miss")
    void getReturnsNullOnMiss() {
        when(cache.getAsMap(anyString())).thenReturn(null);

        assertNull(playUrlCache.get(null, "migu", "60054701986"));
        assertEquals(0, playUrlCache.getHitCount());
        assertEquals(1, playUrlCache.getMissCount());
    }

    @Test
    @DisplayName("空 Map / url 缺失 / url 空白都按未命中处理（不返回空串）")
    void getTreatsEmptyOrBlankAsMiss() {
        when(cache.getAsMap(anyString())).thenReturn(Map.of());
        assertNull(playUrlCache.get(null, "migu", "1"));

        when(cache.getAsMap(anyString())).thenReturn(Map.of("src", "kugou"));
        assertNull(playUrlCache.get(null, "migu", "1"));

        when(cache.getAsMap(anyString())).thenReturn(Map.of("url", "   "));
        assertNull(playUrlCache.get(null, "migu", "1"));

        assertEquals(0, playUrlCache.getHitCount());
        assertEquals(3, playUrlCache.getMissCount());
    }

    @Test
    @DisplayName("命中返回 url 与换源平台（供 X-Play-Source 头）")
    void getReturnsUrlAndFailoverSource() {
        when(cache.getAsMap("play:url:v1:anon:netease:186016"))
                .thenReturn(Map.of("url", "https://kugou/x.mp3", "src", "kugou"));

        PlayUrlCache.Entry entry = playUrlCache.get(null, "netease", "186016");

        assertNotNull(entry);
        assertEquals("https://kugou/x.mp3", entry.url());
        assertEquals("kugou", entry.src());
        assertEquals(1, playUrlCache.getHitCount());
        assertEquals(0, playUrlCache.getMissCount());
    }

    @Test
    @DisplayName("未换源的条目 src 为 null（不写空字符串）")
    void getReturnsNullSourceWhenNotFailover() {
        when(cache.getAsMap(anyString())).thenReturn(Map.of("url", "https://ne/x.mp3"));

        PlayUrlCache.Entry entry = playUrlCache.get(null, "netease", "3395949712");

        assertNotNull(entry);
        assertNull(entry.src());
    }

    @Test
    @DisplayName("写入用 90s 固定 TTL（对齐网关 urlCache 硬过期取舍，GET 不续期）")
    @SuppressWarnings("unchecked")
    void putUsesFixedTtl() {
        playUrlCache.put(null, "netease", "186016", "https://kugou/x.mp3", "kugou");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(cache).set(eq("play:url:v1:anon:netease:186016"), payload.capture(), ttl.capture());

        assertEquals(Duration.ofSeconds(90), ttl.getValue());
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("https://kugou/x.mp3", body.get("url"));
        assertEquals("kugou", body.get("src"));
    }

    @Test
    @DisplayName("空 URL 不写缓存（否则会把 404 固化 90s）")
    void putSkipsBlankUrl() {
        playUrlCache.put(null, "netease", "1", null, null);
        playUrlCache.put(null, "netease", "1", "  ", null);

        verify(cache, never()).set(anyString(), any(), any());
    }

    @Test
    @DisplayName("作废按同一键删除（403 重试前必须先清，否则反复命中死 URL）")
    void invalidateDeletesSameKey() {
        playUrlCache.invalidate("7", "kugou", "6BC433A2C149FFC43F8309C73A537601");

        verify(cache).delete("play:url:v1:7:kugou:6BC433A2C149FFC43F8309C73A537601");
    }
}
