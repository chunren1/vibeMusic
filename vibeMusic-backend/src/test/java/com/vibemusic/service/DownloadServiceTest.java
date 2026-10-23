package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DownloadService 单元测试（不启动 Spring，不下载真实文件）。
 *
 * <p>核心契约：对象存储不可用时跳过「下载到临时文件 + 上传 MinIO」，
 * 只解析播放地址并入库，返回稳定的 {@code /api/download/file/{sourceId}}。</p>
 */
@DisplayName("DownloadService 存储不可用时的降级")
class DownloadServiceTest {

    private final NeteaseApiService neteaseApiService = mock(NeteaseApiService.class);
    private final StorageService storageService = mock(StorageService.class);
    private final SongService songService = mock(SongService.class);
    private final SongPlayService songPlayService = mock(SongPlayService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private final DownloadService downloadService = new DownloadService(
            neteaseApiService, storageService, songService, songPlayService, transactionTemplate);

    @Test
    @DisplayName("存储不可用：不下载临时文件、不上传，仍返回 fileUrl 并入库")
    void downloadSkipsUploadWhenStorageUnavailable() {
        when(storageService.isAvailable()).thenReturn(false);
        when(songPlayService.getPlayUrl("s1")).thenReturn("https://p1.music.126.net/a.mp3");
        // 事务模板直接执行回调（不入真实事务）
        doAnswer(inv -> {
            Consumer<?> callback = inv.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        String url = downloadService.download("s1", "晴天", "周杰伦", "叶惠美", "", 269, "exhigh");

        assertThat(url).isEqualTo("/api/download/file/s1");
        verify(neteaseApiService, never()).downloadSongToFile(anyString());
        verify(storageService, never()).uploadStream(anyString(), any(), anyLong(), anyString());
        verify(storageService, never()).getDirectUrl(anyString());
        verify(songService).saveDownloadedSong("s1", "晴天", "周杰伦", "叶惠美", "", 269,
                "/api/download/file/s1");
    }

    @Test
    @DisplayName("存储不可用且取不到播放地址：抛 502，不写库")
    void downloadFailsWhenPlayUrlUnavailable() {
        when(storageService.isAvailable()).thenReturn(false);
        when(songPlayService.getPlayUrl("s2")).thenReturn(null);

        assertThatThrownBy(() -> downloadService.download("s2", "歌", "手", "", "", 100, "exhigh"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(502));

        verifyNoInteractions(songService, neteaseApiService, transactionTemplate);
    }
}
