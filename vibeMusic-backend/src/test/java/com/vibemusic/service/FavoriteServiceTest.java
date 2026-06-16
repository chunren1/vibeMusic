package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FavoriteService 单元测试")
class FavoriteServiceTest extends TransactionalServiceTest {

    @Autowired
    private FavoriteService favoriteService;

    @Test
    @DisplayName("toggle → 首次收藏返回 true")
    void shouldToggleOn() {
        boolean result = favoriteService.toggle(1L, "newFav001", "歌曲名", "歌手", "http://cover.jpg");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("toggle → 取消收藏返回 false")
    void shouldToggleOff() {
        // 晴天已存在于测试数据中 (user_id=1)
        boolean result = favoriteService.toggle(1L, "000rh0dE2TyUic", "晴天", "周杰伦", null);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("toggle → 重复操作正确切换")
    void shouldToggleTwice() {
        String sid = "toggleTest001";
        assertThat(favoriteService.toggle(1L, sid, "Test", "Artist", null)).isTrue();  // 收藏
        assertThat(favoriteService.toggle(1L, sid, "Test", "Artist", null)).isFalse(); // 取消
        assertThat(favoriteService.toggle(1L, sid, "Test", "Artist", null)).isTrue();  // 再收藏
    }

    @Test
    @DisplayName("list → 返回收藏列表")
    void shouldListFavorites() {
        List<Map<String, Object>> list = favoriteService.list(1L, 10);
        assertThat(list).isNotEmpty();
        assertThat(list.get(0)).containsKeys("sourceId", "songName", "artist");
        assertThat(list.get(0).get("sourceId")).isEqualTo("000rh0dE2TyUic");
    }

    @Test
    @DisplayName("list → count 限制生效")
    void shouldLimitCount() {
        // 收藏几首歌
        for (int i = 0; i < 5; i++) {
            favoriteService.toggle(1L, "favSong" + i, "Song" + i, "Artist", null);
        }
        List<Map<String, Object>> list = favoriteService.list(1L, 3);
        assertThat(list).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("favoritesSet → 返回 sourceId 集合")
    void shouldReturnFavoritesSet() {
        Set<String> set = favoriteService.favoritesSet(1L);
        assertThat(set).contains("000rh0dE2TyUic");
    }
}
