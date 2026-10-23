package com.vibemusic.service;

import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;

import java.util.List;
import java.util.Map;

/**
 * 推荐侧纯规则（从 RecommendService 抽出，行为不变）：
 * 艺人多样性计数、缓存污染判定。无 I/O、无状态。
 */
final class RecommendRules {

    /** 同一艺人最多保留几首（推荐去重口径）。 */
    static final int MAX_PER_ARTIST = 2;

    private RecommendRules() {
    }

    static String artistBucket(String artist) {
        return (artist == null || artist.isEmpty()) ? "" : artist;
    }

    static boolean tryCountArtist(Map<String, Integer> artistCount, String artist) {
        String bucket = artistBucket(artist);
        int used = artistCount.getOrDefault(bucket, 0);
        if (used >= MAX_PER_ARTIST) {
            return false;
        }
        artistCount.put(bucket, used + 1);
        return true;
    }

    /**
     * 缓存污染判定：结果 ≥4 首却全部来自单一平台 → 大概率另外两平台当时异常，
     * 这种缓存不能继续喂给用户。
     */
    static boolean isCachePoisoned(RecommendResult result) {
        List<SongDTO> songs = result.getSongs();
        if (songs == null || songs.size() < 4) return false;
        long neteaseCount = songs.stream().filter(s -> "netease".equals(s.getPlatform())).count();
        long qqCount = songs.stream().filter(s -> "qq".equals(s.getPlatform())).count();
        long miguCount = songs.stream().filter(s -> "migu".equals(s.getPlatform())).count();
        return neteaseCount == songs.size() || qqCount == songs.size() || miguCount == songs.size();
    }
}
