package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.config.NeteaseApiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NeteaseApiService 薄封装分支测试（Mockito 纯单元测试，不依赖 musicapi）
 * <p>
 * 覆盖各 API 透传方法与下载白名单拒绝分支。
 */
@DisplayName("NeteaseApiService 透传与白名单测试")
class NeteaseApiServiceTest {

    private RestTemplate restTemplate;
    private NeteaseApiService apiService;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        NeteaseApiConfig config = new NeteaseApiConfig();
        apiService = new NeteaseApiService(config, restTemplate, RestClient.builder());
        ReflectionTestUtils.setField(apiService, "cdnWhitelistConfig",
                "*.music.126.net,*.migu.cn");
        lenient().when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(Map.of("code", 200), HttpStatus.OK));
    }

    @Test
    @DisplayName("getSongUrl 空 level 默认 exhigh，正常透传 body")
    void shouldDefaultLevelToExhigh() {
        Map<String, Object> body = apiService.getSongUrl("123", null);
        assertNotNull(body);
        assertEquals(200, body.get("code"));
        verify(restTemplate).exchange(argThat(uri ->
                        uri.toString().contains("/song/url/v1")
                                && uri.toString().contains("level=exhigh")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("各透传方法命中对应 musicapi 路径")
    void shouldHitExpectedPaths() {
        apiService.personalizedPlaylists(5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/personalized")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getNeteasePlaylist("1");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/netease/playlist_detail")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getQQPlaylist("2");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/qq/playlist")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.searchNetease("晴天", 5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/netease/search")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.searchQQ("晴天", 5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/qq/search")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.searchMigu("晴天", 5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/migu/search")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getLyric("3");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/lyric")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getQQLyric("abc");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/qq/lyric")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getQQSongUrl("abc");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/song/url/qq")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getMiguSongUrl("60054701986", null);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/migu/url")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.searchKugou("晴天", 5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/kugou/search")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getKugouSongUrl("abc123hash", "12345", "low");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/kugou/url")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getKugouLyric("abc123hash", "12345");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/kugou/lyric")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.searchBili("晴天", 5);
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/bili/search")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        apiService.getBiliSongUrl("BV1De411p77r");
        verify(restTemplate).exchange(argThat(u -> u.toString().contains("/bili/url")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("酷狗搜索透传 hash 列表并携带 keyword/limit 参数")
    void shouldSearchKugouWithHashList() {
        Map<String, Object> song = Map.of("hash", "abc123hash", "320hash", "def456hash",
                "album_id", 12345, "name", "晴天");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(Map.of("code", 200, "data", java.util.List.of(song)),
                        HttpStatus.OK));

        Map<String, Object> body = apiService.searchKugou("晴天", 5);

        assertNotNull(body);
        java.util.List<?> data = (java.util.List<?>) body.get("data");
        assertEquals(1, data.size());
        assertEquals("abc123hash", ((Map<?, ?>) data.get(0)).get("hash"));
        verify(restTemplate).exchange(argThat(uri ->
                        uri.toString().contains("/kugou/search")
                                && uri.toString().contains("limit=5")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("酷狗取链 low/standard 透传 level，缺省 level 默认 low")
    void shouldResolveKugouUrlTierWithFallback() {
        Map<String, Object> ok = Map.of("code", 200, "data",
                java.util.List.of(Map.of("hash", "abc123hash", "url", "http://cdn.kugou/x.mp3")));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(ok, HttpStatus.OK));

        Map<String, Object> low = apiService.getKugouSongUrl("abc123hash", "12345", "low");
        assertNotNull(low);
        apiService.getKugouSongUrl("abc123hash", "12345", "standard");
        Map<String, Object> def = apiService.getKugouSongUrl("abc123hash", null, null);
        assertNotNull(def);

        verify(restTemplate, times(2)).exchange(argThat(uri ->
                        uri.toString().contains("/kugou/url")
                                && uri.toString().contains("level=low")
                                && uri.toString().contains("hash=abc123hash")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        verify(restTemplate, times(1)).exchange(argThat(uri ->
                        uri.toString().contains("/kugou/url")
                                && uri.toString().contains("level=standard")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        verify(restTemplate, times(1)).exchange(argThat(uri ->
                        uri.toString().contains("/kugou/url")
                                && uri.toString().contains("level=low")
                                && uri.toString().contains("albumId=&")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("酷狗取链 high/super 档 phase1 直接返回 null，不请求上游")
    void shouldReturnNullForKugouVipTiers() {
        clearInvocations(restTemplate);

        assertNull(apiService.getKugouSongUrl("abc123hash", "12345", "high"));
        assertNull(apiService.getKugouSongUrl("abc123hash", "12345", "super"));
        assertNull(apiService.getKugouSongUrl("abc123hash", "12345", " SQ "));

        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("酷狗歌词透传 hash/albumId 并解析 lyric 字段")
    void shouldGetKugouLyric() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(
                        Map.of("code", 200, "data", Map.of("lyric", "[00:01]晴天")),
                        HttpStatus.OK));

        Map<String, Object> body = apiService.getKugouLyric("abc123hash", "12345");

        assertNotNull(body);
        assertEquals("[00:01]晴天", ((Map<?, ?>) body.get("data")).get("lyric"));
        verify(restTemplate).exchange(argThat(uri ->
                        uri.toString().contains("/kugou/lyric")
                                && uri.toString().contains("hash=abc123hash")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("B站搜索透传 keyword/limit，取链透传 bvid/复合 id")
    void shouldSearchBiliAndResolveUrl() {
        Map<String, Object> song = Map.of("id", "BV1De411p77r", "name", "少年");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(Map.of("code", 200, "data", java.util.List.of(song)),
                        HttpStatus.OK));

        Map<String, Object> body = apiService.searchBili("少年", 5);

        assertNotNull(body);
        java.util.List<?> data = (java.util.List<?>) body.get("data");
        assertEquals(1, data.size());
        verify(restTemplate).exchange(argThat(uri ->
                        uri.toString().contains("/bili/search")
                                && uri.toString().contains("limit=5")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));

        clearInvocations(restTemplate);
        Map<String, Object> ok = Map.of("code", 200, "data",
                java.util.List.of(Map.of("id", "BV1De411p77r", "url", "https://xy.bilivideo.com/x.m4s")));
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                        any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<Map>(ok, HttpStatus.OK));

        assertNotNull(apiService.getBiliSongUrl("BV1De411p77r"));
        assertNotNull(apiService.getBiliSongUrl("BV1De411p77r|171776208"));
        verify(restTemplate, times(2)).exchange(argThat(uri ->
                        uri.toString().contains("/bili/url")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("非白名单下载链接直接 403 拒绝，不建临时文件")
    void shouldRejectNonWhitelistedDownloadUrl() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> apiService.downloadSongToFile("http://evil.example/x.mp3"));
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("非法 URL 下载同样拒绝，不抛错到调用方之外")
    void shouldRejectMalformedDownloadUrl() {
        assertThrows(BusinessException.class,
                () -> apiService.downloadSongToFile("not a url at all ://"));
    }
}
