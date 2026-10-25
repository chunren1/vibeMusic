package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.PlaylistAddSongRequest;
import com.vibemusic.dto.PlaylistCreateRequest;
import com.vibemusic.dto.PlaylistDeleteBatchRequest;
import com.vibemusic.dto.PlaylistImportRequest;
import com.vibemusic.dto.PlaylistReorderRequest;
import com.vibemusic.dto.PlaylistUpdateRequest;
import com.vibemusic.service.JsonCacheService;
import com.vibemusic.service.NeteaseApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PlaylistController.songs IDOR 回归测试（纯单测，不启动 Spring 上下文）。
 * Service 层归属校验（403/404）见 PlaylistServiceTest.GetSongsTest。
 */
@DisplayName("PlaylistController.songs IDOR 回归测试")
class PlaylistControllerTest {

    @Test
    @DisplayName("未登录调用写操作应返回 401（不再静默假成功）")
    void guestMutationsReturn401() {
        SecurityContextHolder.clearContext();
        PlaylistController controller = new PlaylistController(null, null, null);

        assertEquals(401, controller.create(new PlaylistCreateRequest("x", null, null)).getCode());
        assertEquals(401, controller.addSong(new PlaylistAddSongRequest(1L, "s1", null, null, null, null, null)).getCode());
        assertEquals(401, controller.removeSong(1L, "s1").getCode());
        // 2026-10-11 审查补齐：剩余 5 处写操作同样不得静默假成功
        assertEquals(401, controller.update(new PlaylistUpdateRequest(1L, "x", null, null)).getCode());
        assertEquals(401, controller.reorder(new PlaylistReorderRequest(List.of())).getCode());
        assertEquals(401, controller.delete(1L).getCode());
        assertEquals(401, controller.deleteBatch(new PlaylistDeleteBatchRequest(List.of(1L))).getCode());
        assertEquals(401, controller.importPlaylist(new PlaylistImportRequest("netease", "123")).getCode());
    }

    @Test
    @DisplayName("未登录访问 songs 应返回 200 空结果（guest-ok 文件约定），且不触碰 Service")
    void guestSongsReturnsEmptyOkWithoutTouchingService() {
        SecurityContextHolder.clearContext();
        // Service 传 null：若 Controller 敢在 guest 路径下调用 Service 会直接 NPE 失败
        PlaylistController controller = new PlaylistController(null, null, null);

        Result<List<Map<String, Object>>> result = controller.songs(1L);

        assertEquals(200, result.getCode());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("musicapi 挂掉时 detail 应返回错误信封而非抛异常（503 中文提示）")
    void detailDegradesWhenUpstreamThrows() {
        NeteaseApiService netease = mock(NeteaseApiService.class);
        JsonCacheService cache = mock(JsonCacheService.class);
        when(cache.getAsMap(anyString())).thenReturn(null);
        when(netease.getNeteasePlaylist("123")).thenThrow(new RuntimeException("musicapi down"));
        PlaylistController controller = new PlaylistController(null, netease, cache);

        Result<Map<String, Object>> result = controller.detail("netease", "123");

        assertEquals(503, result.getCode());
        assertEquals("歌单服务暂不可用，请稍后重试", result.getMessage());
        assertNull(result.getData());
    }
}
