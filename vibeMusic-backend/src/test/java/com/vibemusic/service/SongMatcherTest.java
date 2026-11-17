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

    private static Map<String, Object> song(String name, String artists, long durationMs) {
        return Map.of("name", name, "artists", artists, "duration", durationMs);
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
    @DisplayName("feat. 后缀：仍算同一首（中性标注）")
    void acceptsNeutralSuffix() {
        List<Map<String, Object>> results = List.of(song("晴天 (feat. 某歌手)", "周杰伦"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦")).isNotNull();
    }

    @Test
    @DisplayName("Live / 翻唱 / 伴奏版：换源必须拒绝（同名不同录音）")
    void rejectsLiveAndCoverVersions() {
        assertThat(SongMatcher.findBestMatch(List.of(song("晴天 (Live)", "周杰伦")), "晴天", "周杰伦")).isNull();
        assertThat(SongMatcher.findBestMatch(List.of(song("晴天 翻唱版", "周杰伦")), "晴天", "周杰伦")).isNull();
        assertThat(SongMatcher.findBestMatch(List.of(song("晴天 伴奏", "周杰伦")), "晴天", "周杰伦")).isNull();
        assertThat(SongMatcher.findBestMatch(List.of(song("晴天 (Remix)", "周杰伦")), "晴天", "周杰伦")).isNull();
    }

    @Test
    @DisplayName("歌名本身含版本词（Live Forever）：两边都带，照常匹配")
    void acceptsWhenBothSidesContainVersionWord() {
        List<Map<String, Object>> results = List.of(song("Live Forever", "Oasis"));

        assertThat(SongMatcher.findBestMatch(results, "Live Forever", "Oasis")).isNotNull();
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

    @Test
    @DisplayName("同名翻唱（歌手不同）：换源必须拒绝")
    void rejectsSameNameDifferentArtist() {
        // 线上真实案例：搜索「晴天 周杰伦」的第一条是翻唱「晴天 RyaVocal」，
        // 歌名完全一致、歌手完全不同，光看歌名会直接选中。
        List<Map<String, Object>> results = List.of(song("晴天", "RyaVocal"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦")).isNull();
    }

    @Test
    @DisplayName("多歌手：任一 token 匹配即放行")
    void acceptsPartialArtistMatch() {
        List<Map<String, Object>> results = List.of(song("All For Love", "Tungevaag"));

        assertThat(SongMatcher.findBestMatch(results, "All For Love",
                "Tungevaag / Raaban / Richard Smitt")).isNotNull();
    }

    @Test
    @DisplayName("时长差超过 5 秒：拒绝（DJ/剪辑版的典型特征）")
    void rejectsWhenDurationFarOff() {
        List<Map<String, Object>> results = List.of(
                song("晴天", "周杰伦", 185_000L),
                song("晴天", "周杰伦", 269_000L));

        // 期望 269s；第一条 185s 差 84s → 应被淘汰，命中第二条
        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦", 269_000L))
                .isEqualTo(song("晴天", "周杰伦", 269_000L));
    }

    @Test
    @DisplayName("只有一个候选且时长差过大：返回 null（宁可不播，也不放错版本）")
    void rejectsSoleCandidateWithWrongDuration() {
        List<Map<String, Object>> results = List.of(song("晴天", "周杰伦", 185_000L));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦", 269_000L)).isNull();
    }

    @Test
    @DisplayName("时长在容忍范围内（不同平台取整差异）：放行")
    void acceptsWhenDurationWithinTolerance() {
        List<Map<String, Object>> results = List.of(song("晴天", "周杰伦", 266_500L));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦", 269_000L)).isNotNull();
    }

    @Test
    @DisplayName("候选缺歌手字段：拒绝（2026-11-16 收紧，缺字段等于无法校验）")
    void rejectsWhenCandidateArtistMissing() {
        // 旧口径是「缺字段不等于错歌」，但换源候选来自各平台搜索，正常都有 artists；
        // 缺失往往意味着结构异常或搬运号上传。线上「歌名对、声音不对」的案例里，
        // 这类无人认领的候选贡献了一部分，宁可放弃也不赌。
        List<Map<String, Object>> results = List.of(Map.of("name", "晴天"));

        assertThat(SongMatcher.findBestMatch(results, "晴天", "周杰伦")).isNull();
    }
}
