package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("首页推荐质量门槛：只留单曲")
class RecommendSingleFilterTest {

    private static SongDTO song(String id, String name, String artist, Integer duration) {
        return SongDTO.builder()
                .sourceId(id).name(name).artist(artist).duration(duration)
                .build();
    }

    @Test
    @DisplayName("正常单曲通过")
    void acceptsNormalSingle() {
        assertTrue(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "晴天", "周杰伦", 269)));
    }

    @Test
    @DisplayName("B 站视频一律不进推荐位")
    void rejectsBilibili() {
        assertFalse(RecommendSingleFilter.isSingle(song("BV1xx411c7mD", "晴天", "周杰伦", 269)));
    }

    @Test
    @DisplayName("太短（片段/试听）与太长（整张专辑）都拒")
    void rejectsBadDuration() {
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "晴天", "周杰伦", 30)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "晴天", "周杰伦", 20 * 60)));
    }

    @Test
    @DisplayName("时长缺失放行——部分平台不返回 duration，卡死会误伤")
    void acceptsMissingDuration() {
        assertTrue(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "晴天", "周杰伦", null)));
    }

    @Test
    @DisplayName("合集类标题不进推荐位")
    void rejectsPlaylistTitles() {
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "周杰伦经典合集", "周杰伦", 269)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "华语金曲串烧", "群星", 269)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "Best Playlist 2026", "群星", 269)));
    }

    @Test
    @DisplayName("缺歌名/歌手/ID 的一律拒")
    void rejectsIncomplete() {
        assertFalse(RecommendSingleFilter.isSingle(null));
        assertFalse(RecommendSingleFilter.isSingle(song("", "晴天", "周杰伦", 269)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", " ", "周杰伦", 269)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "晴天", null, 269)));
    }
}
