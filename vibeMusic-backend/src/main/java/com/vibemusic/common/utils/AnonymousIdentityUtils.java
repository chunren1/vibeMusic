package com.vibemusic.common.utils;

/**
 * 匿名用户身份标识工具
 * <p>
 * 复用推荐模块已建立的约定：{@code X-Device-Id} 请求头 / {@code deviceId} 请求参数，
 * 两者并存时 {@code deviceId} 参数优先（与 RecommendController 一致）。
 * 清洗后可安全嵌入 Redis Key（仅保留 {@code [A-Za-z0-9_-]}，截断至 64 字符）。
 */
public final class AnonymousIdentityUtils {

    private AnonymousIdentityUtils() {
    }

    /** 缺省匿名标识（无设备信息时使用） */
    public static final String FALLBACK = "anon";

    /** 标识最大长度（防超长 Key） */
    public static final int MAX_LENGTH = 64;

    /**
     * 按既定优先级解析匿名标识：deviceId 参数优先，其次 X-Device-Id 请求头。
     *
     * @param paramDeviceId  deviceId 请求参数（可为 null）
     * @param headerDeviceId X-Device-Id 请求头（可为 null）
     * @return 清洗后的标识，空白时返回 {@link #FALLBACK}
     */
    public static String resolveAnonymousId(String paramDeviceId, String headerDeviceId) {
        String raw = paramDeviceId != null ? paramDeviceId : headerDeviceId;
        return sanitize(raw);
    }

    /**
     * 清洗标识：去除 Redis Key 非法/危险字符并截断长度。
     *
     * @param raw 原始标识（可为 null）
     * @return 仅含 {@code [A-Za-z0-9_-]} 的字符串，空白时返回 {@link #FALLBACK}
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK;
        }
        String cleaned = raw.strip().replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.isEmpty()) {
            return FALLBACK;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
