package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预热策略：真实性门槛的口径。
 *
 * <p>这些常量/判定直接决定「服务器上存的是不是原版」，所以钉死：
 * 平台白名单只有网易云与 QQ，时长容差与 {@code SongMatcher} 同口径，
 * 候选时长未知一律判不符（宁可少缓存，也不放过一个 DJ 版）。</p>
 */
@DisplayName("热歌缓存真实性策略")
class HotCacheWarmerPolicyTest {

    @Test
    @DisplayName("官方平台白名单只有 netease 与 qq")
    void officialPlatforms() {
        assertEquals(2, HotCacheWarmer.OFFICIAL_PLATFORMS.size());
        assertTrue(HotCacheWarmer.OFFICIAL_PLATFORMS.contains("netease"));
        assertTrue(HotCacheWarmer.OFFICIAL_PLATFORMS.contains("qq"));
        assertFalse(HotCacheWarmer.OFFICIAL_PLATFORMS.contains("kugou"));
        assertFalse(HotCacheWarmer.OFFICIAL_PLATFORMS.contains("migu"));
        assertFalse(HotCacheWarmer.OFFICIAL_PLATFORMS.contains("bilibili"));
    }

    @Test
    @DisplayName("时长容差 5s 起步，长曲按 3%")
    void durationTolerance() {
        // 4 分钟的歌：容差 max(5s, 7.2s) = 7.2s
        assertTrue(HotCacheWarmer.durationCompatible(240_000L, 243_000L));
        assertTrue(HotCacheWarmer.durationCompatible(240_000L, 233_000L));
        // DJ 版常见 +30s 以上，必须拒绝
        assertFalse(HotCacheWarmer.durationCompatible(240_000L, 271_000L));
    }

    @Test
    @DisplayName("候选时长未知直接判不符")
    void unknownCandidateDurationRejected() {
        assertFalse(HotCacheWarmer.durationCompatible(240_000L, null));
        assertFalse(HotCacheWarmer.durationCompatible(240_000L, 0L));
        assertFalse(HotCacheWarmer.durationCompatible(240_000L, -1L));
    }

    @Test
    @DisplayName("低于 320k 的档位不进缓存")
    void minQualityRank() {
        assertEquals(3, HotCacheWarmer.MIN_QUALITY_RANK);
        assertFalse(CrossPlatformFailover.qualityRank("128") >= HotCacheWarmer.MIN_QUALITY_RANK);
        assertFalse(CrossPlatformFailover.qualityRank("AAC") >= HotCacheWarmer.MIN_QUALITY_RANK);
        assertFalse(CrossPlatformFailover.qualityRank("unknown") >= HotCacheWarmer.MIN_QUALITY_RANK);
        assertTrue(CrossPlatformFailover.qualityRank("320") >= HotCacheWarmer.MIN_QUALITY_RANK);
        assertTrue(CrossPlatformFailover.qualityRank("FLAC") >= HotCacheWarmer.MIN_QUALITY_RANK);
    }
}
