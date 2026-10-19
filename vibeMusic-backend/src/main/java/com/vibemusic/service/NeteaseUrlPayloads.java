package com.vibemusic.service;

import java.util.List;
import java.util.Map;

/**
 * 网易云取链响应（{@code /song/url/v1} 原样透传体）条目级判定集（纯函数，无副作用）。
 *
 * <p>背景：网易云用 {@code fee} 表达曲目的付费属性、{@code payed} 表达本次请求是否已取得
 * 播放权限、{@code freeTrialInfo} 表达"只能试听"。
 * <p>"无版权"（{@code fee=0} + 空链）与"付费曲目但当前 Cookie 无播放权限"
 * （{@code fee!=0} + {@code payed!=1}）是两类完全不同的事实：
 * 前者应跳过昂贵的跨平台降级，后者必须继续降级（QQ 兜底）；
 * 混为一谈会把 VIP 掉档误判成无版权，导致 App 侧 404。
 */
final class NeteaseUrlPayloads {

    private NeteaseUrlPayloads() {
    }

    /**
     * 试听/付费口径：{@code fee} 非 0 即付费曲目
     * （1=VIP 会员曲、4=购买专辑曲、8=低音质免费），缺失非数字一律不判付费。
     */
    static boolean isPaidTrack(Map<?, ?> item) {
        Integer fee = intValue(item.get("fee"));
        return fee != null && fee != 0;
    }

    /** {@code payed=1} 表示本次请求已拿到播放权限；缺失/0 一律视为未授权。 */
    static boolean isFullyPayed(Map<?, ?> item) {
        Integer payed = intValue(item.get("payed"));
        return payed != null && payed == 1;
    }

    /** 是否有非空 url（可能是试听片段；试听判定见 {@code freeTrialInfo}）。 */
    static boolean hasPlayableUrl(Map<?, ?> item) {
        Object url = item.get("url");
        return url instanceof String s && !s.isBlank();
    }

    /** 是否有完整可播链接：url 非空且无试听信息。 */
    static boolean hasUsableFullUrl(Map<?, ?> item) {
        return hasPlayableUrl(item) && item.get("freeTrialInfo") == null;
    }

    /**
     * VIP/权限受限判定：{@code data} 中任一条目为"付费曲目 + 未取得播放权限 + 拿不到完整可播链接"
     * （空链或试听片段）即 true。
     *
     * <p>实测口径（2026-10 主 Cookie 掉 VIP）：VIP-only 曲目返回
     * {@code code=200, url=<30s 试听>, fee=1, payed=0, freeTrialInfo!=null}；
     * 换用有 VIP 的 Cookie 则为 {@code payed=1, freeTrialInfo=null}。
     * 无版权（{@code fee=0, url=null}）与已授权曲目（{@code payed=1} 或拿到完整 url）均不命中，
     * 保证只有"真实权限受限"才触发共享 Cookie 能力快切。
     */
    static boolean isVipBlocked(Map<String, Object> body) {
        if (body == null) return false;
        List<?> data = asList(body.get("data"));
        if (data == null || data.isEmpty()) return false;
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> m)) continue;
            if (!isPaidTrack(m) || isFullyPayed(m)) continue;
            if (hasUsableFullUrl(m)) continue;
            return true;
        }
        return false;
    }

    /** {@code data} 中是否存在完整可播条目（快切重试后的采用判定，试听不算可播）。 */
    static boolean isFullPlayable(Map<String, Object> body) {
        if (body == null) return false;
        List<?> data = asList(body.get("data"));
        if (data == null) return false;
        for (Object item : data) {
            if (item instanceof Map<?, ?> m && hasUsableFullUrl(m)) return true;
        }
        return false;
    }

    /** 宽松取列表：非 List 一律 null（空列表视为"无条目"，由调用方各自判定）。 */
    static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : null;
    }

    /** 宽松取整数：Number 或数字字符串；其余 null（缺字段/脏值不参与判定）。 */
    static Integer intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
