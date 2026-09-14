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
