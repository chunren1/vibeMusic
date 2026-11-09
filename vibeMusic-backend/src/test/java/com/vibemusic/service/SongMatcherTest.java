package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级选曲匹配的回归测试。
 *
 * <p>线上问题（2026-11-09）：点歌 404 → 网易云按名换源时直接取搜索结果第一条，
 * 导致「歌单里显示 A、实际播的是 B」。这里锁住两条规则：
 * 歌名对不上一律淘汰；没有任何可信候选时返回 {@code null}（宁可不播，也不放错）。
 */
class SongMatcherTest {

    private static Map<String, Object> song(String name, String artists) {
        return Map.of("name", name, "artists", artists);
    }

    @Test
    @DisplayName("同名同歌手：命中并返回该条")
    void picksExactMatch() {
        List<Map<String, Object>> results = List.of(
                song("稻香", "周杰伦"),
                song("晴天", "周杰伦"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦"))
                .isEqualTo(song("晴天", "周杰伦"));
    }

    @Test
    @DisplayName("同歌手但歌名不同：一条都不选（旧实现在这里会取第一条）")
    void rejectsDifferentSongBySameArtist() {
        List<Map<String, Object>> results = List.of(
                song("稻香", "周杰伦"),
                song("七里香", "周杰伦"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦")).isNull();
    }

    @Test
    @DisplayName("歌名带 Live 后缀：仍算同一首")
    void acceptsSuffixedName() {
        List<Map<String, Object>> results = List.of(song("晴天 (Live)", "周杰伦"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦")).isNotNull();
    }

    @Test
    @DisplayName("短名不误配：「告白」不会命中「告白气球」")
    void rejectsShortNameContainment() {
        List<Map<String, Object>> results = List.of(song("告白气球", "周杰伦"));

        assertThat(SongMatcher.findBestMatch(results, "告白", "周杰伦")).isNull();
    }

    @Test
    @DisplayName("空结果 / null 歌名：返回 null 而不是抛异常")
    void handlesEmptyInput() {
        assertThat(SongMatcher.findBestMatch(List.of(), "晴天", "周杰伦")).isNull();
        assertThat(SongMatcher.findBestMatch(null, "晴天", "周杰伦")).isNull();
        assertThat(SongMatcher.findBestMatch(List.of(song("晴天", "周杰伦")), null, "周杰伦")).isNull();
    }

    @Test
    @DisplayName("歌手缺失时只校验歌名")
    void matchesByNameWhenArtistMissing() {
        List<Map<String, Object>> results = List.of(song("晴天", ""));

        assertThat(SongMatcher.findBestMatch(results, "晴天", null)).isNotNull();
    }
}
