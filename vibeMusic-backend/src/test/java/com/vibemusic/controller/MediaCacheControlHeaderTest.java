package com.vibemusic.controller;

import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.RecommendService;
import com.vibemusic.service.SongPlayService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.StorageService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 带声媒体响应头契约测试（不启动 Spring）。
 *
 * <p>这两条路径吐出的字节来自**服务端自有 VIP 账号协商出的签名 URL**，URL 对所有请求者相同，
 * 因此响应**绝不能**声明 {@code Cache-Control: public}（= 允许任何共享缓存跨用户复用这些字节）。
 * 浏览器私有缓存的好处要靠 {@code private} 保留。</p>
 *
 * <p>背景（2026-10-19 实测）：Spring Security 框架默认已给所有响应写
 * {@code no-cache, no-store, max-age=0, must-revalidate}；但控制器里的 {@code setHeader}
 * 晚于框架写入 → 会**覆盖**成 public。故此处必须显式钉住 private。</p>
 */
@DisplayName("带声媒体响应缓存语义：必须 private（禁 public 共享缓存）")
class MediaCacheControlHeaderTest {

    private static final String PRIVATE_24H = "private, max-age=86400";

    @Test
    @DisplayName("StreamController MinIO 直读分支：Cache-Control 必须是 private")
    void streamMinioDirectMustBePrivate() throws Exception {
        StorageService storageService = mock(StorageService.class);
        when(storageService.exists("songs/song-1.mp3")).thenReturn(true);
        when(storageService.getObjectSize("songs/song-1.mp3")).thenReturn(1024L);
        when(storageService.getObject("songs/song-1.mp3"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        StreamController controller = new StreamController(
                mock(SongPlayService.class),
                mock(PlayHistoryService.class),
                storageService,
                mock(RecommendService.class),
                mock(com.vibemusic.service.AudioProxyService.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Range")).thenReturn(null);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        controller.stream("song-1", "歌名", "歌手", "netease", request, response);

        verify(response).setHeader("Cache-Control", PRIVATE_24H);
        verify(response, never()).setHeader(eq("Cache-Control"), contains("public"));
    }

    @Test
    @DisplayName("DownloadController 文件下载：Cache-Control 必须是 private")
    void fileDownloadMustBePrivate() throws Exception {
        StorageService storageService = mock(StorageService.class);
        when(storageService.isAvailable()).thenReturn(true);
        when(storageService.exists("songs/song-2.mp3")).thenReturn(true);
        when(storageService.getObject("songs/song-2.mp3"))
                .thenReturn(new ByteArrayInputStream(new byte[]{9, 8, 7}));
        SongService songService = mock(SongService.class);
        when(songService.getBySourceId("song-2")).thenReturn(null);

        DownloadController controller = new DownloadController(
                mock(com.vibemusic.service.DownloadService.class),
                storageService,
                songService,
                mock(com.vibemusic.service.AudioProxyService.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        controller.fileDownload("song-2", request, response);

        verify(response).setHeader("Cache-Control", PRIVATE_24H);
        verify(response, never()).setHeader(eq("Cache-Control"), contains("public"));
    }
}
