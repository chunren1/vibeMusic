package com.vibemusic.contract;

import com.vibemusic.controller.StreamController;
import com.vibemusic.service.AudioProxyService;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.RecommendService;
import com.vibemusic.service.SongPlayService;
import com.vibemusic.service.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 音频流 Range 契约（App 播放器与 Web audio 都按这套响应头做 seek）。
 *
 * <p>锁的是「响应形状」，不是内容：状态码、{@code Accept-Ranges}、{@code Content-Range}、
 * {@code Content-Length}、{@code Content-Type}。播放器一旦拿不到 206 + Content-Range，
 * 拖动进度条就会失效（部分客户端直接不播）。上游/存储都在这里 mock，不连 MinIO 也不连网络。</p>
 *
 * <p>为什么用 MockHttpServletRequest/Response 而不是 MockMvc：{@code /stream} 是
 * {@code void} 端点、直接往 response 里写字节，MockMvc 拿到的 body 与真实容器一致但会
 * 额外加载整个 Spring 上下文；这里只需要控制器与存储的交互契约。</p>
 */
@DisplayName("音频流 Range 契约")
class StreamRangeContractTest {

    private static final String SOURCE_ID = "test-source-1";
    private static final String OBJECT_NAME = "songs/" + SOURCE_ID + ".mp3";
    private static final long FILE_SIZE = 1000L;

    /** 构造一份内容可辨认的假音频（0..999 循环），便于断言切片起始位置。 */
    private static byte[] fakeAudio(long size) {
        byte[] data = new byte[(int) size];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static StorageService storageWith(long size) {
        StorageService storage = mock(StorageService.class);
        when(storage.exists(OBJECT_NAME)).thenReturn(true);
        when(storage.getObjectSize(OBJECT_NAME)).thenReturn(size);
        when(storage.getObject(OBJECT_NAME))
                .thenAnswer(inv -> new ByteArrayInputStream(fakeAudio(size)));
        when(storage.getObjectRange(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    long offset = inv.getArgument(1);
                    long length = inv.getArgument(2);
                    byte[] all = fakeAudio(size);
                    int from = (int) offset;
                    int to = (int) Math.min(all.length, offset + length);
                    return new ByteArrayInputStream(Arrays.copyOfRange(all, from, to));
                });
        return storage;
    }

    private static StreamController controller(StorageService storage, AudioProxyService proxy) {
        return new StreamController(mock(SongPlayService.class), mock(PlayHistoryService.class),
                storage, mock(RecommendService.class), proxy);
    }

    @Test
    @DisplayName("Range: bytes=100-199 → 206 + Content-Range/Content-Length 精确")
    void partialRangeReturns206() {
        StorageService storage = storageWith(FILE_SIZE);
        AudioProxyService proxy = mock(AudioProxyService.class);
        StreamController controller = controller(storage, proxy);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/stream");
        request.addHeader("Range", "bytes=100-199");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.stream(SOURCE_ID, null, null, null, request, response);

        assertEquals(206, response.getStatus(), "部分请求必须 206，否则播放器无法 seek");
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("bytes 100-199/" + FILE_SIZE, response.getHeader("Content-Range"));
        assertEquals("100", response.getHeader("Content-Length"));
        assertEquals("audio/mpeg", response.getHeader("Content-Type"));
        assertEquals(100, response.getContentAsByteArray().length);
        // 切片从第 100 字节开始（内容可辨认，能抓 off-by-one）
        byte[] expected = fakeAudio(FILE_SIZE);
        assertEquals(expected[100], response.getContentAsByteArray()[0]);
        verify(storage, times(1)).getObjectRange(OBJECT_NAME, 100L, 100L);
        verify(proxy, never()).proxy(anyString(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Range: bytes=0- → 206 + 全文件范围（末端自动取 size-1）")
    void openEndedRangeReturns206() {
        StorageService storage = storageWith(FILE_SIZE);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/stream");
        request.addHeader("Range", "bytes=0-");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(storage, mock(AudioProxyService.class))
                .stream(SOURCE_ID, null, null, null, request, response);

        assertEquals(206, response.getStatus());
        assertEquals("bytes 0-" + (FILE_SIZE - 1) + "/" + FILE_SIZE, response.getHeader("Content-Range"));
        assertEquals(String.valueOf(FILE_SIZE), response.getHeader("Content-Length"));
        assertEquals(FILE_SIZE, response.getContentAsByteArray().length);
    }

    @Test
    @DisplayName("无 Range → 200 全量，且不带 Content-Range")
    void fullRequestReturns200() {
        StorageService storage = storageWith(FILE_SIZE);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(storage, mock(AudioProxyService.class))
                .stream(SOURCE_ID, null, null, null, request, response);

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Content-Range"));
        assertEquals("bytes", response.getHeader("Accept-Ranges"), "即使无 Range 也应声明支持");
        assertEquals(String.valueOf(FILE_SIZE), response.getHeader("Content-Length"));
        assertEquals("audio/mpeg", response.getHeader("Content-Type"));
        assertEquals(FILE_SIZE, response.getContentAsByteArray().length);
        verify(storage, never()).getObjectRange(anyString(), anyLong(), anyLong());
    }
}
