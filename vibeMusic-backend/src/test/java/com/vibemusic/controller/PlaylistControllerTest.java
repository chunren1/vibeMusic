package com.vibemusic.controller;

import com.vibemusic.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

        assertEquals(401, controller.create(Map.of("name", "x")).getCode());
        assertEquals(401, controller.addSong(Map.of("playlistId", 1L, "sourceId", "s1")).getCode());
        assertEquals(401, controller.removeSong(1L, "s1").getCode());
        // 2026-10-11 审查补齐：剩余 5 处写操作同样不得静默假成功
        assertEquals(401, controller.update(Map.of("playlistId", 1L, "name", "x")).getCode());
        assertEquals(401, controller.reorder(Map.of("order", List.of())).getCode());
        assertEquals(401, controller.delete(1L).getCode());
        assertEquals(401, controller.deleteBatch(Map.of("ids", List.of(1))).getCode());
        assertEquals(401, controller.importPlaylist(Map.of("source", "netease", "id", "123")).getCode());
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
}
