package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.entity.Song;
import com.vibemusic.service.AudioProxyService;
import com.vibemusic.service.DownloadService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.StorageService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DownloadController 单元测试（不启动 Spring）。
 *
 * <p>核心契约：对象存储不可用时，{@code /api/download/file/{id}} 不再 404，
 * 而是委托 {@link AudioProxyService} 以上游直连方式流式转发（asAttachment=true）。</p>
 */
@DisplayName("DownloadController 下载降级链路")
class DownloadControllerTest {

    private final DownloadService downloadService = mock(DownloadService.class);
    private final StorageService storageService = mock(StorageService.class);
    private final SongService songService = mock(SongService.class);
    private final AudioProxyService audioProxyService = mock(AudioProxyService.class);
    private final DownloadController controller =
            new DownloadController(downloadService, storageService, songService, audioProxyService);

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    @Test
    @DisplayName("存储不可用时 /file/{id} 走上游直连代理（attachment=true），不返回 404")
    void fileDownloadFallsBackToProxyWhenStorageUnavailable() {
        when(storageService.isAvailable()).thenReturn(false);
        when(songService.getBySourceId("s1")).thenReturn(null);

        controller.fileDownload("s1", request, response);

        verify(audioProxyService).proxy("s1", null, null, null, request, response, true, "s1");
        verify(storageService, never()).getObject(anyString());
        verify(storageService, never()).exists(anyString());
        verify(response, never()).setStatus(404);
    }

    @Test
    @DisplayName("存储可用且已缓存时直读 MinIO，不调用代理")
    void fileDownloadReadsMinioWhenCached() throws Exception {
        when(storageService.isAvailable()).thenReturn(true);
        when(storageService.exists("songs/s2.mp3")).thenReturn(true);
        when(storageService.getObject("songs/s2.mp3"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        Song song = new Song();
        song.setName("晴天");
        song.setArtist("周杰伦");
        when(songService.getBySourceId("s2")).thenReturn(song);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        controller.fileDownload("s2", request, response);

        verify(storageService).getObject("songs/s2.mp3");
        verifyNoInteractions(audioProxyService);
        verify(response).setHeader(eq("Content-Disposition"), contains("attachment"));
    }

    @Test
    @DisplayName("存储可用但对象不存在时同样降级为直连代理")
    void fileDownloadFallsBackWhenObjectMissing() {
        when(storageService.isAvailable()).thenReturn(true);
        when(storageService.exists("songs/s3.mp3")).thenReturn(false);
        when(songService.getBySourceId("s3")).thenReturn(null);

        controller.fileDownload("s3", request, response);

        verify(audioProxyService).proxy("s3", null, null, null, request, response, true, "s3");
    }

    @Test
    @DisplayName("check 端点：存储不可用时返回 true（可走上游直连代理）")
    void checkReturnsTrueWhenStorageUnavailable() {
        when(storageService.isAvailable()).thenReturn(false);
        when(storageService.exists("songs/s4.mp3")).thenReturn(false);

        Result<Boolean> result = controller.check("s4");

        assertThat(result.getData()).isTrue();
    }

    @Test
    @DisplayName("POST 下载：存储不可用时仍返回可用的 fileUrl（该 URL 走直连代理）")
    void downloadReturnsFileUrlWhenStorageUnavailable() {
        when(storageService.isAvailable()).thenReturn(false);
        when(storageService.exists(anyString())).thenReturn(false);
        when(downloadService.download(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyString())).thenReturn("/api/download/file/s5");

        Result<Map<String, Object>> result = controller.download("s5",
                Map.of("name", "晴天", "artist", "周杰伦", "duration", 269, "level", "exhigh"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("fileUrl", "/api/download/file/s5");
    }
}
