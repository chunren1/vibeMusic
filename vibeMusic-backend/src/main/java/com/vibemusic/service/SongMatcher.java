package com.vibemusic.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨平台降级时的候选匹配器（从 SongPlayService 抽出，行为不变）。
 *
 * <p>只做纯计算：按「歌名 + 歌手」的 token 重叠率挑最像的一条，首字相同加 0.2；
 * 分数低于 0.15 时退回上游第一条。无 I/O、无状态，可直接单测。</p>
 */
final class SongMatcher {

    private SongMatcher() {
    }

    static Map<String, Object> findBestMatch(List<Map<String, Object>> results,
                                             String expectedName, String expectedArtist) {
        Map<String, Object> best = null;
        double bestScore = 0;
        String target = (expectedName + " " + (expectedArtist != null ? expectedArtist : "")).toLowerCase().trim();
        for (Map<String, Object> item : results) {
            String candName = item.get("name") != null ? String.valueOf(item.get("name")) : "";
            String candArtist = item.get("artists") != null ? String.valueOf(item.get("artists")) : "";
            String candidate = (candName + " " + candArtist).toLowerCase().trim();
            double score = similarity(target, candidate);
            // 歌名首字相同加分
            if (!candName.isEmpty() && !expectedName.isEmpty() && candName.charAt(0) == expectedName.charAt(0)) {
                score += 0.2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore > 0.15 ? best : results.get(0); // 分数太低降级取第一条
    }

    /** 简单 token 重叠率 */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("\\s+")));
        int overlap = 0;
        for (String s : setA) {
            if (setB.contains(s)) overlap++;
        }
        double jaccard = (double) overlap / Math.max(setA.size(), setB.size());
        // 包含关系 bonus
        if (a.contains(b) || b.contains(a)) return Math.max(jaccard, 0.6);
        return jaccard;
    }
}
