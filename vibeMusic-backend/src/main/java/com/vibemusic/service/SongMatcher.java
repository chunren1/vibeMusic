package com.vibemusic.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨平台降级时的候选匹配器（从 SongPlayService 抽出，行为不变）。
 *
 * <p>只做纯计算：按「歌名 + 歌手」的 token 重叠率挑最像的一条，首字相同加 0.2。
 * 无 I/O、无状态，可直接单测。</p>
 *
 * <p><b>2026-11-09 收紧</b>：候选必须<b>先过歌名校验</b>（相等，或双方长度都 ≥3 时互相包含），
 * 且最终分数须 > 0.15，否则返回 {@code null}。旧实现在分数不足时退回
 * {@code results.get(0)}——那正是「歌单显示 A、实际播 B」的直接来源。</p>
 */
final class SongMatcher {

    private SongMatcher() {
    }

    static Map<String, Object> findBestMatch(List<Map<String, Object>> results,
                                             String expectedName, String expectedArtist) {
        if (results == null || results.isEmpty() || expectedName == null) return null;
        Map<String, Object> best = null;
        double bestScore = 0;
        String targetName = expectedName.toLowerCase().trim();
        String target = (targetName + " " + (expectedArtist != null ? expectedArtist : "")).toLowerCase().trim();
        for (Map<String, Object> item : results) {
            if (item == null) continue;
            String candName = item.get("name") != null ? String.valueOf(item.get("name")) : "";
            String candArtist = item.get("artists") != null ? String.valueOf(item.get("artists")) : "";
            if (!nameCompatible(targetName, candName.toLowerCase().trim())) continue;
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
        // 没有可信候选就放弃：播放失败用户能理解，放错歌不能
        return bestScore > 0.15 ? best : null;
    }

    /** 版本后缀的起始标记：括号、方括号、连字符、feat/ft。命中即视为同一首的不同版本。 */
    private static final String[] VERSION_MARKS = {"(", "（", "[", "【", "-", "—", "·", "feat", "ft."};

    /**
     * 歌名是否可能是同一首（前置硬校验）。
     *
     * <p>为什么不能只靠 {@link #similarity} 的总分：它按空格切 token，中文歌名整体是
     * 一个 token，于是「同歌手、不同歌」靠歌手那一 token 就能拿到 0.5 分——远超阈值。
     * 只判总分时，点《晴天》会被换成《稻香》。歌名对不上一律排除。
     *
     * <p>三条放行规则：
     * <ol>
     *   <li>完全相等；</li>
     *   <li>一方只是另一方加了版本后缀（「晴天」vs「晴天 (Live)」）——中文短歌名只有两三个
     *       字，只靠长度阈值会把「晴天」「稻香」这类主流歌名全部挡在门外；</li>
     *   <li>双方长度都 ≥3 时互相包含（容忍译名、副标题）。</li>
     * </ol>
     */
    private static boolean nameCompatible(String target, String candidate) {
        if (target.isEmpty() || candidate.isEmpty()) return false;
        if (target.equals(candidate)) return true;
        if (onlyVersionSuffixed(target, candidate)) return true;
        return target.length() >= 3 && candidate.length() >= 3
                && (target.contains(candidate) || candidate.contains(target));
    }

    /** 一方是否只是另一方加了版本后缀（两段都已小写、去首尾空白）。 */
    private static boolean onlyVersionSuffixed(String a, String b) {
        String rest;
        if (b.startsWith(a)) {
            rest = b.substring(a.length());
        } else if (a.startsWith(b)) {
            rest = a.substring(b.length());
        } else {
            return false;
        }
        String trimmed = rest.trim();
        if (trimmed.isEmpty()) return true;
        for (String mark : VERSION_MARKS) {
            if (trimmed.startsWith(mark)) return true;
        }
        return false;
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
