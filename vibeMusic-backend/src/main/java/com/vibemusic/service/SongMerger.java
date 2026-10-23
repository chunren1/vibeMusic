package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五平台搜索结果合并器（从 SongSearchService 抽出，行为不变）。
 *
 * <p>职责边界：只做「归一化去重 → 跨平台合并加分 → 相关性/非 VIP/标题长度/B 站播放门排序」，
 * 不关心上游调用、熔断、缓存。平台权重由调用方按 {@code effectiveWeight(...)} 算好传入，
 * 这样合并器本身无状态、可直接单测。</p>
 */
@Slf4j
final class SongMerger {

    static final double MERGE_BONUS_PER_SOURCE = 0.5;
    static final double MERGE_BONUS_MAX = 1.0;
    static final double NETEASE_HEAD_BONUS = 0.5;

    private SongMerger() {
    }

    /**
     * @param weights 平台 → 已生效权重（netease/qq/migu/kugou/bilibili）
     */
    static List<SongDTO> merge(List<SongDTO> neteaseSongs, List<SongDTO> qqSongs,
                               List<SongDTO> miguSongs, List<SongDTO> kugouSongs,
                               List<SongDTO> biliSongs, String keyword, Map<String, Double> weights) {
        Map<String, SongDTO> mergedMap = new LinkedHashMap<>();
        Map<String, Integer> mergeCounts = new HashMap<>();

        addPlatformSongs(mergedMap, mergeCounts, neteaseSongs, weightOf(weights, "netease"), "netease", false);
        addPlatformSongs(mergedMap, mergeCounts, qqSongs, weightOf(weights, "qq"), "qq", true);
        addPlatformSongs(mergedMap, mergeCounts, miguSongs, weightOf(weights, "migu"), "migu", true);
        addPlatformSongs(mergedMap, mergeCounts, kugouSongs, weightOf(weights, "kugou"), "kugou", true);
        addPlatformSongs(mergedMap, mergeCounts, biliSongs, weightOf(weights, "bilibili"), "bilibili", true);

        List<SongDTO> merged = new ArrayList<>(mergedMap.values());
        long relevanceStart = System.currentTimeMillis();
        SongRanking.applyRelevanceBonus(merged, keyword);
        SongRanking.applyNonVipBonus(merged);
        SongRanking.applyTitleLengthPenalty(merged);
        SongRanking.applyBiliPlayGate(merged);
        log.info("[SEARCH-RANK] 相关性重排: keyword='{}', count={}, relevance-cost={}ms",
                keyword, merged.size(), System.currentTimeMillis() - relevanceStart);
        merged.sort((a, b) -> Double.compare(
                b.getFinalScore() != null ? b.getFinalScore() : 0,
                a.getFinalScore() != null ? a.getFinalScore() : 0));
        return merged;
    }

    private static double weightOf(Map<String, Double> weights, String platform) {
        Double w = weights.get(platform);
        return w != null ? w : 1.0;
    }

    private static void addPlatformSongs(Map<String, SongDTO> mergedMap, Map<String, Integer> mergeCounts,
                                         List<SongDTO> songs, double weight, String platform,
                                         boolean mergeOnDuplicate) {
        for (int i = 0; i < songs.size(); i++) {
            SongDTO song = songs.get(i);
            song.setPlatform(platform);
            song.setAvailableSources(new ArrayList<>(List.of(platform)));
            double base = SongRanking.computeScore(i + 1, weight);
            if ("netease".equals(platform) && i == 0) base += NETEASE_HEAD_BONUS;
            song.setFinalScore(base);
            String key = SongRanking.normalizeKey(song.getName(), song.getArtist());
            SongDTO existing = mergedMap.get(key);

            if (existing != null && mergeOnDuplicate) {
                List<String> allSources = new ArrayList<>(existing.getAvailableSources());
                if (!allSources.contains(platform)) allSources.add(platform);
                SongDTO winner = SongRanking.pickBest(existing, song);
                winner.setAvailableSources(allSources);
                int merges = mergeCounts.merge(key, 1, Integer::sum);
                double prevBonus = Math.min(MERGE_BONUS_PER_SOURCE * (merges - 1), MERGE_BONUS_MAX);
                double bonus = Math.min(MERGE_BONUS_PER_SOURCE * merges, MERGE_BONUS_MAX);
                double baseExisting = existing.getFinalScore() - prevBonus;
                winner.setFinalScore(Math.max(baseExisting, song.getFinalScore()) + bonus);
                mergedMap.put(key, winner);
            } else {
                // 同平台重复键保留分数更高者（见 SongRanking.pickBestByScore 的修复说明）
                mergedMap.put(key, existing != null ? SongRanking.pickBestByScore(existing, song) : song);
            }
        }
    }
}
