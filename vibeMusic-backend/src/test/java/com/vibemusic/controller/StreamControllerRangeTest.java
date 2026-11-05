package com.vibemusic.controller;

import com.vibemusic.service.AudioProxyService;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.RecommendService;
import com.vibemusic.service.SongPlayService;
import com.vibemusic.service.StorageService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * StreamController MinIO 直读分支的 Range 语义（不启动 Spring）。
 *
 * <p>旧缺陷：{@code if (start >= fileSize) start = fileSize - 1;} 会把越界请求
 * 伪装成 206（Content-Range 起点与请求不一致、body 只有 1 字节），客户端拿到
 * {@code ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE}。这里钉住 416 语义。</p>
 */
@DisplayName("StreamController MinIO 直读 Range：越界必须 416")
class StreamControllerRangeTest {

    private static final String OBJECT = "songs/s1.mp3";
    private static final long SIZE = 1000L;

    private StorageService storageService;
    private AudioProxyService audioProxyService;
    private StreamController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() throws IOException {
        storageService = mock(StorageService.class);
        audioProxyService = mock(AudioProxyService.class);
        controller = new StreamController(
                mock(SongPlayService.class),
                mock(PlayHistoryService.class),
                storageService,
                mock(RecommendService.class),
                audioProxyService);

        when(storageService.exists(OBJECT)).thenReturn(true);
        when(storageService.getObjectSize(OBJECT)).thenReturn(SIZE);
        when(storageService.getObject(OBJECT))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(storageService.getObjectRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override public void write(int b) { /* 单测丢弃字节 */ }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { /* 单测无需异步写 */ }
        });
    }

    private void stream() {
        controller.stream("s1", "歌名", "歌手", "netease", request, response);
    }

    @Test
    @DisplayName("start >= fileSize → 416 + Content-Range: bytes */<size>，不写 body 也不回落代理")
    void startBeyondFileSizeReturns416() {
        when(request.getHeader("Range")).thenReturn("bytes=" + SIZE + "-");

        stream();

        verify(response).setStatus(416);
        verify(response).setHeader("Content-Range", "bytes */" + SIZE);
        verify(response, never()).setStatus(206);
        verify(response, never()).setStatus(200);
        verify(storageService, never()).getObjectRange(anyString(), anyLong(), anyLong());
        verify(audioProxyService, never()).proxy(anyString(), any(), any(), any(), any(), any(),
                anyBoolean(), any());
    }

    @Test
    @DisplayName("end < start（bytes=500-100）→ 416，不伪造 206")
    void invertedRangeReturns416() {
        when(request.getHeader("Range")).thenReturn("bytes=500-100");

        stream();

        verify(response).setStatus(416);
        verify(response).setHeader("Content-Range", "bytes */" + SIZE);
        verify(storageService, never()).getObjectRange(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("end 超出 fileSize-1 → 收敛到末字节，Content-Range 不越界")
    void endIsClampedToLastByte() {
        when(request.getHeader("Range")).thenReturn("bytes=0-999999");

        stream();

        verify(response).setStatus(206);
        verify(response).setHeader("Content-Range", "bytes 0-999/" + SIZE);
        verify(response).setContentLengthLong(SIZE);
        verify(storageService).getObjectRange(OBJECT, 0L, SIZE);
    }

    @Test
    @DisplayName("无 Range → 200 + 全文件长度（setContentLengthLong）")
    void fullFileWithoutRange() {
        when(request.getHeader("Range")).thenReturn(null);

        stream();

        verify(response, never()).setStatus(416);
        verify(response).setContentLengthLong(SIZE);
        verify(storageService).getObject(OBJECT);
    }
}
