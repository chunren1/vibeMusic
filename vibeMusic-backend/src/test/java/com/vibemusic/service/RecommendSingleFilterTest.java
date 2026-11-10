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
    @DisplayName("歌名等于搜索热词的榜单条目拒掉，但歌名里含「民谣」的正常歌曲保留")
    void rejectsGenericTitlesButKeepsRealOnes() {
        assertFalse(RecommendSingleFilter.isSingle(song("BFE4C8CA32F33FE66AB20A", "流行", "5", 234)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "热歌", "某某", 234)));
        // 这两首是真实单曲，不能因为歌名含「民谣」被误杀
        assertTrue(RecommendSingleFilter.isSingle(song("9EE1D188F985E416DC8440", "中华民谣", "孙浩", 253)));
        assertTrue(RecommendSingleFilter.isSingle(song("1411558182", "东北民谣", "毛不易", 279)));
    }

    @Test
    @DisplayName("聚合账号（华语群星等）下的条目拒掉")
    void rejectsAggregateArtists() {
        assertFalse(RecommendSingleFilter.isSingle(song("05FBB0D813A09C3454C493", "我和我的祖国", "华语群星", 243)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "某歌", "群星", 243)));
        assertFalse(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "某歌", "Various Artists", 243)));
    }

    @Test
    @DisplayName("搬运式长标题（含《》且超过 16 字）拒掉")
    void rejectsLongBracketTitles() {
        assertFalse(RecommendSingleFilter.isSingle(
                song("08BD73B567C168AA716015", "华语群星《烟火里的尘埃 (节奏版)》", "乐贤baby", 360)));
        // 短书名号歌名是正常单曲
        assertTrue(RecommendSingleFilter.isSingle(song("001Qu4I30eVFYb", "《晴天》", "周杰伦", 269)));
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
