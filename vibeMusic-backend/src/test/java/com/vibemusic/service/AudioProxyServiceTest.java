package com.vibemusic.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.*;

/**
 * AudioProxyService 单元测试（不启动 Spring、不发起真实 HTTP）。
 *
 * <p>该类是音频代理的**唯一实现**：播放（asAttachment=false）与下载（asAttachment=true）共用，
 * 这里钉住两者的头部差异。</p>
 */
@DisplayName("AudioProxyService 下载/播放头部分支")
class AudioProxyServiceTest {

    private final SongPlayService songPlayService = mock(SongPlayService.class);
    private final AudioProxyService service =
            new AudioProxyService(songPlayService, mock(RestClient.Builder.class));

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    @DisplayName("asAttachment=true：写 Content-Disposition（UTF-8 文件名）")
    void attachmentModeSetsContentDisposition() {
        // 取不到播放地址 → 走 404 终局，但头部必须先落好
        when(songPlayService.getPlayUrl("s1", null, null, null)).thenReturn(null);

        service.proxy("s1", null, null, null, request, response, true, "周杰伦 - 晴天");

        String expected = "attachment; filename*=UTF-8''"
                + URLEncoder.encode("周杰伦 - 晴天.mp3", StandardCharsets.UTF_8);
        verify(response).setHeader("Content-Disposition", expected);
        verify(response).setStatus(404);
    }

    @Test
    @DisplayName("asAttachment=false（播放）：不写 Content-Disposition")
    void playModeDoesNotSetContentDisposition() {
        when(songPlayService.getPlayUrl("s2", "歌名", "歌手", "netease")).thenReturn(null);

        service.proxy("s2", "歌名", "歌手", "netease", request, response, false, null);

        verify(response, never()).setHeader(eq("Content-Disposition"), anyString());
        verify(response).setStatus(404);
    }
}
