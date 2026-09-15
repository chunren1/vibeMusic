package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.JsonCacheService;
import com.vibemusic.service.NeteaseApiService;
import com.vibemusic.service.SongSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SongController 纯单元测试（Mockito 直调，不启动 Spring）
 * <p>
 * 覆盖 banner 缓存命中/空结果/异常、lyric 双平台与哨兵分支、
 * parseLrc 空行与空文本。
 */
@DisplayName("SongController 分支单元测试")
class SongControllerUnitTest {

    private SongSearchService songSearchService;
    private NeteaseApiService neteaseApiService;
    private JsonCacheService cache;
    private SongController controller;

    @BeforeEach
    void setUp() {
        songSearchService = mock(SongSearchService.class);
        neteaseApiService = mock(NeteaseApiService.class);
        cache = mock(JsonCacheService.class);
        controller = new SongController(songSearchService, neteaseApiService, cache);
    }

    @Test
    @DisplayName("banner 缓存命中直接返回，不碰 musicapi")
    void shouldReturnCachedBanner() {
        List<Map<String, Object>> cached = List.of(Map.of("name", "x"));
        when(cache.getAsList("banner:v2:home")).thenReturn(cached);

        Result<List<Map<String, Object>>> result = controller.banner();

        assertEquals(200, result.getCode());
        assertSame(cached, result.getData());
        verify(neteaseApiService, never()).personalizedPlaylists(anyInt());
    }

    @Test
    @DisplayName("banner 未命中时映射 musicapi 结果并回写缓存，http 封面升级 https")
    void shouldMapAndCacheBanner() {
        when(cache.getAsList("banner:v2:home")).thenReturn(null);
        when(neteaseApiService.personalizedPlaylists(5)).thenReturn(Map.of("result", List.of(
                Map.of("name", "热歌", "picUrl", "http://p1.music.126.net/x.jpg",
                        "copywriter", "编辑推荐", "playCount", 100))));

        Result<List<Map<String, Object>>> result = controller.banner();

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("https://p1.music.126.net/x.jpg", result.getData().get(0).get("coverUrl"));
        verify(cache).set(eq("banner:v2:home"), any(), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("banner 接口 result 缺失时返回空列表")
    void shouldReturnEmptyWhenNoResult() {
        when(cache.getAsList("banner:v2:home")).thenReturn(null);
        when(neteaseApiService.personalizedPlaylists(5)).thenReturn(Map.of());

        assertTrue(controller.banner().getData().isEmpty());
    }

    @Test
    @DisplayName("banner 接口异常时返回空列表，不抛错")
    void shouldReturnEmptyOnBannerFailure() {
        when(cache.getAsList("banner:v2:home")).thenReturn(null);
        when(neteaseApiService.personalizedPlaylists(5)).thenThrow(new RuntimeException("down"));

        assertTrue(controller.banner().getData().isEmpty());
    }

    @Test
    @DisplayName("lyric 缓存命中直接返回")
    void shouldReturnCachedLyric() {
        List<Map<String, Object>> cached = List.of(Map.of("time", 1.0, "text", "hi"));
        when(cache.getAsList("lyric:v3:123")).thenReturn(cached);

        assertSame(cached, controller.lyric("123").getData());
        verify(neteaseApiService, never()).getLyric(anyString());
    }

    @Test
    @DisplayName("lyric QQ 分支解析 data.lyric 并回写长缓存")
    void shouldResolveQqLyric() {
        when(cache.getAsList("lyric:v3:000rh0dE2TyUic")).thenReturn(null);
        when(neteaseApiService.getQQLyric("000rh0dE2TyUic"))
                .thenReturn(Map.of("data", Map.of("lyric", "[00:01.50]哈喽")));

        Result<List<Map<String, Object>>> result = controller.lyric("000rh0dE2TyUic");

        assertEquals(1, result.getData().size());
        assertEquals(1.5, ((Number) result.getData().get(0).get("time")).doubleValue(), 1e-9);
        verify(cache).set(eq("lyric:v3:000rh0dE2TyUic"), any(), eq(Duration.ofDays(365)));
    }

    @Test
    @DisplayName("lyric 网易云分支解析 lrc.lyric")
    void shouldResolveNeteaseLyric() {
        when(cache.getAsList("lyric:v3:123")).thenReturn(null);
        when(neteaseApiService.getLyric("123"))
                .thenReturn(Map.of("lrc", Map.of("lyric", "[00:02.25]你好")));

        Result<List<Map<String, Object>>> result = controller.lyric("123");

        assertEquals(1, result.getData().size());
        assertEquals(2.25, ((Number) result.getData().get(0).get("time")).doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("lyric 空结果写哨兵防穿透：null/data 缺失/空串")
    void shouldSetEmptySentinelOnMissingLyric() {
        when(cache.getAsList(anyString())).thenReturn(null);
        when(neteaseApiService.getQQLyric("qq-null")).thenReturn(null);
        assertTrue(controller.lyric("qq-null").getData().isEmpty());

        when(neteaseApiService.getQQLyric("qq-nodata")).thenReturn(Map.of());
        assertTrue(controller.lyric("qq-nodata").getData().isEmpty());

        when(neteaseApiService.getLyric("456")).thenReturn(null);
        assertTrue(controller.lyric("456").getData().isEmpty());

        when(neteaseApiService.getLyric("789")).thenReturn(Map.of());
        assertTrue(controller.lyric("789").getData().isEmpty());

        when(neteaseApiService.getLyric("000")).thenReturn(Map.of("lrc", Map.of("lyric", "")));
        assertTrue(controller.lyric("000").getData().isEmpty());

        verify(cache, atLeastOnce()).setEmpty(anyString(), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("lyric 接口异常返回空列表，不抛错")
    void shouldReturnEmptyOnLyricFailure() {
        when(cache.getAsList(anyString())).thenReturn(null);
        when(neteaseApiService.getLyric(anyString())).thenThrow(new RuntimeException("down"));

        assertTrue(controller.lyric("999").getData().isEmpty());
    }

    @Test
    @DisplayName("parseLrc 跳过空行与非时间行，空文本回退音符")
    void shouldSkipBlankLinesAndFallbackNote() {
        List<Map<String, Object>> lines = SongController.parseLrc("[00:01.5]   \n\n不是时间行\n[00:02]有词");
        assertEquals(2, lines.size());
        assertEquals("♪", lines.get(0).get("text"));
        assertEquals("有词", lines.get(1).get("text"));
    }
}
