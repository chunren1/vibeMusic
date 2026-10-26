package com.vibemusic.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@code AudioProxyService} 403 → 作废短缓存 → 换新 URL 重试 的接线测试。
 *
 * <p>守的是「加了播放链接缓存之后才出现的死路」：{@code proxy()} 本来就是 5 次重试，
 * 若 CDN 返回 403/404 时<strong>不先作废缓存</strong>，5 次重试会全部拿到同一个过期 URL，
 * 把一个本来能自愈的路径变成必死 500。这条断言直接盯生产调用路径，不测辅助方法。
 */
@DisplayName("AudioProxyService 403 作废缓存并换新 URL 重试")
class AudioProxyServiceInvalidateTest {

    private static final String SRC = "186016";
    private static final String STALE_URL = "https://p3.music.126.net/stale.mp3";
    private static final String FRESH_URL = "https://p4.music.126.net/fresh.mp3";
    private static final byte[] AUDIO = "FAKEAUDIO".getBytes(StandardCharsets.UTF_8);

    private SongPlayService songPlayService;
    private MockRestServiceServer server;
    private AudioProxyService service;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private ByteArrayOutputStream body;

    @BeforeEach
    void setUp() throws IOException {
        songPlayService = mock(SongPlayService.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        service = new AudioProxyService(songPlayService, builder);
        ReflectionTestUtils.setField(service, "cdnWhitelistConfig", "*.music.126.net");
        service.initRestClient();

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override public void write(int b) { body.write(b); }
            @Override public void write(byte[] b, int off, int len) { body.write(b, off, len); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener listener) { /* 单测无需异步写 */ }
        });
    }

    @Test
    @DisplayName("403 时先作废缓存再重试，第二次拿到新 URL 并成功写出字节（不是 500）")
    void forbiddenInvalidatesCacheAndRetriesWithFreshUrl() {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any()))
                .thenReturn(STALE_URL, FRESH_URL);

        server.expect(once(), requestTo(STALE_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(FRESH_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(AUDIO, MediaType.parseMediaType("audio/mpeg")));

        service.proxy(SRC, "晴天", "周杰伦", "netease", request, response, false, null);

        server.verify();
        // 核心不变量：403 那次必须作废缓存（否则重试命中同一死链）
        verify(songPlayService, times(1)).invalidatePlayUrl(SRC, "netease");
        // 重试确实换了一次 URL，且最终是 200 而不是 500
        verify(songPlayService, times(2)).getPlayUrl(anyString(), any(), any(), any());
        verify(response).setStatus(200);
        assertArrayEquals(AUDIO, body.toByteArray());
    }

    @Test
    @DisplayName("404 同样作废缓存（资源不存在时同样不能把死 URL 固化 90 秒）")
    void notFoundAlsoInvalidates() {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any()))
                .thenReturn(STALE_URL, FRESH_URL);

        server.expect(once(), requestTo(STALE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(FRESH_URL))
                .andRespond(withSuccess(AUDIO, MediaType.parseMediaType("audio/mpeg")));

        service.proxy(SRC, "晴天", "周杰伦", "netease", request, response, false, null);

        server.verify();
        verify(songPlayService, times(1)).invalidatePlayUrl(SRC, "netease");
        verify(response).setStatus(200);
        assertArrayEquals(AUDIO, body.toByteArray());
    }
}
