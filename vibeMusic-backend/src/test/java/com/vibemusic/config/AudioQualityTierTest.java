package com.vibemusic.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音质分级映射测试：钉死 level 名双向映射与质量序，
 * 防止并行探测收敛（probeNeteaseParallel）后级别↔枚举的对应关系被改坏。
 */
@DisplayName("音质分级映射测试")
class AudioQualityTierTest {

    private static final AudioQualityTier[] MAPPED = {
            AudioQualityTier.HIRES, AudioQualityTier.EXHIGH,
            AudioQualityTier.HIGHER, AudioQualityTier.STANDARD
    };

    @Test
    @DisplayName("level 名与枚举双向一致（toNeteaseLevel 的逆为 fromNeteaseLevel）")
    void roundTrip() {
        for (AudioQualityTier tier : MAPPED) {
            assertEquals(tier, AudioQualityTier.fromNeteaseLevel(tier.toNeteaseLevel()), tier.name());
        }
    }

    @Test
    @DisplayName("未知/null 级别按 STANDARD 兜底，不抛错")
    void unknownFallsBackToStandard() {
        assertEquals(AudioQualityTier.STANDARD, AudioQualityTier.fromNeteaseLevel("unknown"));
        assertEquals(AudioQualityTier.STANDARD, AudioQualityTier.fromNeteaseLevel(null));
    }

    @Test
    @DisplayName("质量序：LOCAL 最优、HIRES 优于 STANDARD、STANDARD 优于 FALLBACK")
    void ordering() {
        assertTrue(AudioQualityTier.LOCAL.betterThan(AudioQualityTier.HIRES));
        assertTrue(AudioQualityTier.HIRES.betterThan(AudioQualityTier.STANDARD));
        assertTrue(AudioQualityTier.STANDARD.betterThan(AudioQualityTier.FALLBACK));
    }
}
