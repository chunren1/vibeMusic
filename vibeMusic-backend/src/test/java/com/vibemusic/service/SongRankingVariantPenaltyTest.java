package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本变体降权回归测试（2026-11-13）。
 *
 * <p>背景：网易云 search 对「晴天」的前 6 条全是翻唱/钢琴版/女声版，周杰伦原唱排在第 8；
 * QQ 恢复取链后同名翻唱更多。用户在列表里点第一条就听到翻唱——"歌取错了"的观感来自排序，
 * 不是播放链路。降权只在"同名干净版本同时存在"时生效。
 */
class SongRankingVariantPenaltyTest {

    private static SongDTO song(String name, double score) {
        SongDTO s = new SongDTO();
        s.setName(name);
        s.setFinalScore(score);
        return s;
    }

    @Test
    @DisplayName("同名翻唱/女声版在存在干净版本时降权，原唱回到前面")
    void coverVersionsSinkBelowCanonical() {
        List<SongDTO> merged = new ArrayList<>();
        merged.add(song("晴天 (原唱 周杰伦)", 3.0));
        merged.add(song("晴天 (女声版))", 2.8));
        merged.add(song("晴天", 2.25));

        SongRanking.applyVersionVariantPenalty(merged);

        assertEquals(2.0, merged.get(0).getFinalScore(), 0.0001, "带版本词的翻唱应扣 1.0");
        assertEquals(1.8, merged.get(1).getFinalScore(), 0.0001);
        assertEquals(2.25, merged.get(2).getFinalScore(), 0.0001, "干净版本不受影响");
    }

    @Test
    @DisplayName("只有翻唱、没有干净版本时不降权（用户就是搜的 Live 版）")
    void noCanonicalMeansNoPenalty() {
        List<SongDTO> merged = new ArrayList<>();
        merged.add(song("晴天 (Live)", 2.0));
        merged.add(song("晴天 (翻唱版)", 1.5));

        SongRanking.applyVersionVariantPenalty(merged);

        assertEquals(2.0, merged.get(0).getFinalScore(), 0.0001);
        assertEquals(1.5, merged.get(1).getFinalScore(), 0.0001);
    }

    @Test
    @DisplayName("英文单词不误判：Discover 不是 cover，Live Forever 不是 Forever 的版本")
    void englishWordsAreNotFalsePositives() {
        assertFalse(SongRanking.hasVersionMarker("Discover"));
        assertFalse(SongRanking.hasVersionMarker("Discovery"));

        List<SongDTO> merged = new ArrayList<>();
        merged.add(song("Forever", 2.0));
        merged.add(song("Live Forever", 1.8));

        SongRanking.applyVersionVariantPenalty(merged);

        assertEquals(1.8, merged.get(1).getFinalScore(), 0.0001, "Live Forever 与 Forever 是两首歌");
    }

    @Test
    @DisplayName("版本词识别：中英文常见后缀都能命中")
    void detectsCommonVersionMarkers() {
        assertTrue(SongRanking.hasVersionMarker("晴天 (Live)"));
        assertTrue(SongRanking.hasVersionMarker("晴天 伴奏"));
        assertTrue(SongRanking.hasVersionMarker("晴天（钢琴版）"));
        assertTrue(SongRanking.hasVersionMarker("晴天 DJ版"));
        assertTrue(SongRanking.hasVersionMarker("晴天 remix"));
        assertFalse(SongRanking.hasVersionMarker("晴天"));
    }

    @Test
    @DisplayName("降权后分数不为负")
    void neverGoesNegative() {
        List<SongDTO> merged = new ArrayList<>();
        merged.add(song("晴天", 3.0));
        merged.add(song("晴天 (伴奏)", 0.3));

        SongRanking.applyVersionVariantPenalty(merged);

        assertEquals(0.0, merged.get(1).getFinalScore(), 0.0001);
    }
}
