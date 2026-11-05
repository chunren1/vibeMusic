package com.vibemusic.service;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 上游非 2xx 的状态码透传契约（真实缺陷回归）。
 *
 * <p>线上实测（2026-11-06）：{@code curl -H 'Range: bytes=999999999-'} 拿到
 * <strong>200 + audio/mpeg + 202 字节的 416 HTML 错误页</strong>。原因是旧实现在非 2xx 时
 * 不写响应状态（默认 200），却照样把 CDN 错误页当音频写出。Media3 对「200 + 非零 position」
 * 会手动 skip，撞上 202 字节 body 立即 EOF →
 * {@code ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE}（用户看到「播放中断」）。</p>
 *
 * <p>本类钉住三件事：416 必须原样回给客户端；其它非 2xx 必须作废 URL 重试、耗尽回 502；
 * 2xx/206 透传行为不能被这次修复改坏。</p>
 */
@DisplayName("AudioProxyService 非 2xx 状态码透传（416/5xx/2xx）")
class AudioProxyServiceErrorStatusTest {

    private static final String SRC = "3436852214";
    private static final String URL = "https://m701.music.126.net/obj/x.mp3";
    private static final byte[] AUDIO = "FAKEAUDIO".getBytes(StandardCharsets.UTF_8);
    private static final String HTML_416 =
            "<html><head><title>416 Requested Range Not Satisfiable</title></head></html>";

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
        when(request.getHeader("Range")).thenReturn("bytes=999999999-");
        response = mock(HttpServletResponse.class);
        body = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override public void write(int b) { body.write(b); }
            @Override public void write(byte[] b, int off, int len) { body.write(b, off, len); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { /* 单测无需异步写 */ }
        });
    }

    private void proxy() {
        service.proxy(SRC, "歌名", "歌手", "netease", request, response, false, null);
    }

    @Test
    @DisplayName("上游 416 → 客户端拿到 416 + Content-Range，绝不回 200、不转发 HTML 错误页")
    void upstream416IsPassedThrough() throws IOException {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any())).thenReturn(URL);
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header("Content-Range", "bytes */2465167")
                        .contentType(MediaType.TEXT_HTML)
                        .body(HTML_416));

        proxy();

        server.verify();
        verify(response).setStatus(416);
        verify(response).setHeader("Content-Range", "bytes */2465167");
        verify(response).setHeader("Accept-Ranges", "bytes");
        // 核心不变量：不得伪造 200，也不得把错误页当音频写出
        verify(response, never()).setStatus(200);
        verify(response, never()).getOutputStream();
        assertTrue(body.toByteArray().length == 0, "416 分支不应写任何 body");
        // URL 有效（只是 Range 越界），不应作废/重试
        verify(songPlayService, times(1)).getPlayUrl(anyString(), any(), any(), any());
        verify(songPlayService, never()).invalidatePlayUrl(anyString(), any());
    }

    @Test
    @DisplayName("上游 416 且没给 Content-Range → 仍回 416，但不写凭空编造的 Content-Range")
    void upstream416WithoutContentRangeOmitsHeader() throws IOException {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any())).thenReturn(URL);
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .contentType(MediaType.TEXT_HTML)
                        .body(HTML_416));

        proxy();

        server.verify();
        verify(response).setStatus(416);
        verify(response, never()).setHeader(eq("Content-Range"), anyString());
        verify(response, never()).getOutputStream();
    }

    @Test
    @DisplayName("上游 500 → 每次重试都作废 URL，最终 502（绝不以 200 转发错误 body）")
    void upstream500RetriesThenReturns502() throws IOException {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any())).thenReturn(URL);
        server.expect(ExpectedCount.times(5), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>500</html>"));

        proxy();

        server.verify();
        verify(response).setStatus(502);
        verify(response, never()).setStatus(200);
        verify(response, never()).getOutputStream();
        assertTrue(body.toByteArray().length == 0, "502 分支不应写任何 body");
        verify(songPlayService, times(5)).invalidatePlayUrl(SRC, "netease");
    }

    @Test
    @DisplayName("回归：成功 206 仍透传 Content-Range / Content-Type 并写出字节")
    void successful206StillPassedThrough() {
        when(songPlayService.getPlayUrl(anyString(), any(), any(), any())).thenReturn(URL);
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT)
                        .contentType(MediaType.parseMediaType("audio/mpeg"))
                        .header("Content-Range", "bytes 0-6/2465167")
                        .header("Content-Length", String.valueOf(AUDIO.length))
                        .body(AUDIO));

        proxy();

        server.verify();
        verify(response).setStatus(206);
        verify(response).setHeader("Content-Range", "bytes 0-6/2465167");
        verify(response).setContentType("audio/mpeg");
        verify(response).setContentLengthLong(AUDIO.length);
        assertArrayEquals(AUDIO, body.toByteArray());
    }

    @Test
    @DisplayName("纯函数：Content-Range 归一化为 bytes */<total>，解析不出总量则返回 null")
    void contentRangeNormalisation() {
        assertEquals("bytes */2465167",
                AudioProxyService.contentRangeForUnsatisfied("bytes */2465167"));
        assertEquals("bytes */2465167",
                AudioProxyService.contentRangeForUnsatisfied(" bytes 0-0/2465167 "));
        assertNull(AudioProxyService.contentRangeForUnsatisfied(null));
        assertNull(AudioProxyService.contentRangeForUnsatisfied("   "));
        assertNull(AudioProxyService.contentRangeForUnsatisfied("bytes */*"));
        assertNull(AudioProxyService.contentRangeForUnsatisfied("bytes 0-0/"));
    }
}
