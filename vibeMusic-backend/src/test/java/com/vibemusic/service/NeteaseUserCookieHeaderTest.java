package com.vibemusic.service;

import com.vibemusic.config.NeteaseApiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BYOC per-user Cookie 透传测试（纯 Mockito，不依赖 musicapi）。
 *
 * <p>验证：3 参数重载经 X-Vibe-User-Cookie 头透传用户 Cookie；
 * 旧 2 参数签名与空 Cookie 一律不带头（回落网关共享 Cookie → 匿名）。
 */
@TestPropertySource(properties = "COOKIE_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@DisplayName("NeteaseApiService per-user Cookie 透传测试")
class NeteaseUserCookieHeaderTest {

    private static final String COOKIE = "MUSIC_U=user-cookie-a; NMTID=xyz";

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

    @SuppressWarnings("unchecked")
    private HttpHeaders captureHeaders() {
        var captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, atLeastOnce()).exchange(any(URI.class), eq(HttpMethod.GET),
                captor.capture(), eq(Map.class));
        return captor.getValue().getHeaders();
    }

    @Test
    @DisplayName("getSongUrl 3 参数：携带 X-Vibe-User-Cookie 头")
    void shouldAttachUserCookieHeaderOnGetSongUrl() {
        apiService.getSongUrl("123", "exhigh", COOKIE);

        HttpHeaders headers = captureHeaders();
        assertEquals(List.of(COOKIE), headers.get(NeteaseApiService.USER_COOKIE_HEADER));
        assertNotNull(headers.get("User-Agent"));
    }

    @Test
    @DisplayName("getSongUrl 旧签名：不带用户 Cookie 头")
    void shouldOmitHeaderOnLegacyGetSongUrl() {
        apiService.getSongUrl("123", "exhigh");

        HttpHeaders headers = captureHeaders();
        assertNull(headers.get(NeteaseApiService.USER_COOKIE_HEADER));
    }

    @Test
    @DisplayName("getSongUrl 空白 Cookie：不带头（回落共享）")
    void shouldOmitHeaderOnBlankCookie() {
        apiService.getSongUrl("123", "exhigh", "  ");
        apiService.getSongUrl("123", "exhigh", null);

        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET),
                argThat(entity -> ((HttpEntity<?>) entity).getHeaders()
                        .get(NeteaseApiService.USER_COOKIE_HEADER) == null),
                eq(Map.class));
    }

    @Test
    @DisplayName("searchNetease 3 参数：携带头；旧签名不带头")
    void shouldAttachHeaderOnlyOnSearchOverload() {
        apiService.searchNetease("晴天", 5, COOKIE);
        assertEquals(List.of(COOKIE), captureHeaders().get(NeteaseApiService.USER_COOKIE_HEADER));

        clearInvocations(restTemplate);
        apiService.searchNetease("晴天", 5);
        assertNull(captureHeaders().get(NeteaseApiService.USER_COOKIE_HEADER));
    }

    @Test
    @DisplayName("每次调用使用独立 HttpHeaders（无 static 共享）")
    void shouldBuildFreshHeadersPerCall() {
        apiService.getSongUrl("1", "exhigh", COOKIE);
        apiService.getSongUrl("2", "exhigh");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET),
                captor.capture(), eq(Map.class));
        assertEquals(List.of(COOKIE),
                captor.getAllValues().get(0).getHeaders().get(NeteaseApiService.USER_COOKIE_HEADER));
        assertNull(captor.getAllValues().get(1).getHeaders().get(NeteaseApiService.USER_COOKIE_HEADER));
    }
}
