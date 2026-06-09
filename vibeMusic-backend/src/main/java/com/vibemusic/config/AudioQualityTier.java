package com.vibemusic.config;

/**
 * 音频服务质量分级（SLA Tiers）
 * 非用户权限，而是系统资源的自适应调度策略：
 *   RustFS缓存命中 → 直出（跳过API调用）
 *   在线获取时按 HIRES → EXHIGH → HIGHER → STANDARD 逐级降级
 *   所有在线源不可用时 → 返回最后可用结果或降级标记
 */
public enum AudioQualityTier {

    /** RustFS 本地缓存直出（最高优先级，零API调用） */
    LOCAL("本地缓存", 0),

    /** 超高音质 hires 96kHz/24bit */
    HIRES("Hi-Res", 1),

    /** 极高音质 exhigh 48kHz */
    EXHIGH("极高", 2),

    /** 高音质 higher 320kbps */
    HIGHER("高品", 3),

    /** 标准音质 standard 128kbps */
    STANDARD("标准", 4),

    /** 降级兜底（所有API源不可用，或仅返回试听片段） */
    FALLBACK("降级", 5);

    private final String label;
    private final int level;

    AudioQualityTier(String label, int level) {
        this.label = label;
        this.level = level;
    }

    public String getLabel() { return label; }
    public int getLevel() { return level; }

    /** 是否比 other 质量更好（数值越小越好） */
    public boolean betterThan(AudioQualityTier other) {
        return this.level < other.level;
    }

    /**
     * 网易云API参数名映射
     */
    public String toNeteaseLevel() {
        return switch (this) {
            case HIRES -> "hires";
            case EXHIGH -> "exhigh";
            case HIGHER -> "higher";
            case STANDARD -> "standard";
            default -> "standard";
        };
    }
}
