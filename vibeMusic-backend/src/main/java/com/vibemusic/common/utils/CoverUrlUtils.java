package com.vibemusic.common.utils;

/**
 * 封面 URL 净化工具
 * <p>
 * 上游数据（网易/QQ/酷狗等）的封面字段可能缺失或为 null，直接
 * {@code String.valueOf} 会把 null 变成字面量 "null" 下发给客户端——
 * App 侧的封面门会把它当成有效 URL（加载必然失败，表现为"封面空白"）。
 * 统一走这里：null / 空白 / 字面量 "null" → 空串；http 升级为 https
 * （客户端默认禁明文流量，且 CDN 均支持 https）。
 */
public final class CoverUrlUtils {

    private CoverUrlUtils() {
    }

    /**
     * 净化封面 URL。
     *
     * @param raw 原始值（可为 null，类型不限）
     * @return 可安全下发的 URL；无效时返回空串（绝不返回 null / "null"）
     */
    public static String cleanCoverUrl(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return "";
        }
        return s.replace("http://", "https://");
    }
}
