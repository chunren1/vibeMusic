package com.vibemusic.common.utils;

import java.util.List;
import java.util.Locale;

/**
 * B 站 CDN 域名判定单一事实源。
 *
 * <p>收敛前有三处镜像实现（历史最重的 bug 根因就是"多份并行实现只改一处"）：
 * <ul>
 *   <li>{@code SongSearchService} 的封面域名判定（精确 i0/i1/i2.hdslb.com）；</li>
 *   <li>{@code ProxyController.ALLOWED_HOSTS} 里的同类目条目；</li>
 *   <li>{@code StreamController.isBilibiliCdnHost} 的音频 upos 域名判定（bilivideo/hdslb 任意子域）。</li>
 * </ul>
 * 前两者语义一致（封面，精确集合），第三者是音频 CDN（后缀匹配、需强制 Referer），
 * 故此处保留两个语义明确的方法，而不是强行合并成一份"看起来像"的判定。
 */
public final class BiliCdnHosts {

    private BiliCdnHosts() {
    }

    /** 封面 CDN 精确域名集合（搜索结果封面走 /api/image-proxy 代理）。 */
    public static final List<String> COVER_HOSTS = List.of("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com");

    /** 是否 B 站封面 CDN（精确匹配，大小写不敏感）。 */
    public static boolean isCoverHost(String host) {
        if (host == null) return false;
        return COVER_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * 是否 B 站音频 upos CDN：bilivideo/hdslb 的自身或其任意子域。
     * 命中后代理请求必须带 {@code Referer: https://www.bilibili.com/}，否则 403。
     */
    public static boolean isAudioCdnHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("bilivideo.com") || h.endsWith(".bilivideo.com")
                || h.equals("hdslb.com") || h.endsWith(".hdslb.com");
    }
}
