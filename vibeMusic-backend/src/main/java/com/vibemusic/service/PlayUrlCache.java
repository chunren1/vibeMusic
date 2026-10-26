package com.vibemusic.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 播放链接短缓存（Redis）。
 *
 * <p>背景：{@link AudioProxyService} 每次 {@code /api/songs/stream} 请求（含每个 Range 分片、
 * 每次拖动进度、每次断线重连）都会调 {@link SongPlayService#getPlayUrl} 重新取链。生产没有 MinIO
 * （{@code STORAGE_ENABLED=false}），唯一缓存判定恒 false，于是「网易明确无版权」的歌每次都要
 * 烧满 8s 预算——实测 8.8s，且**每拖一次进度都要再等一次**。
 *
 * <p>TTL 取值依据：签名 URL 自身极短命。网关 {@code musicapi} 的 {@code urlCache} 对同类
 * 播放地址取 <b>60s</b> 硬过期（其注释记录「QQ vkey 实测几分钟内返回 403」），且刻意关闭
 * sliding refresh（{@code updateAgeOnGet:false}），否则过期 URL 会因高频命中而永不死。
 * 本类沿用同一取舍：<b>90s 固定过期、GET 不续期</b>，只覆盖「同一会话内的连续 Range 请求」，
 * 不试图跨会话复用。
 *
 * <p>失效自愈：URL 过期后上游 CDN 返回 403/404，{@link AudioProxyService} 会
 * {@link SongPlayService#invalidatePlayUrl} 清掉本条再重试取链——否则 5 次重试会拿到同一个
 * 死 URL，把原本能自愈的路径变成必死 500。
 *
 * <p>Key 含调用者作用域：网易 VIP 曲的播放地址是用<b>请求者自己的 Cookie</b>换来的，
 * 若只按 {@code sourceId} 缓存，会把 VIP 地址泄漏给下一个匿名请求（等于绕过付费）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayUrlCache {

    /** 版本化前缀，与项目 Redis key 约定一致（AGENTS.md「Redis Key 版本化前缀」）。 */
    static final String KEY_PREFIX = "play:url:v1:";

    /** 见类注释：对齐网关 urlCache 的硬过期取舍，只覆盖同会话连续请求。 */
    static final Duration TTL = Duration.ofSeconds(90);

    static final String ANON = "anon";
    static final String AUTO_PLATFORM = "auto";

    private final JsonCacheService cache;

    private final AtomicInteger hit = new AtomicInteger();
    private final AtomicInteger miss = new AtomicInteger();

    /** 可选注入：单测无 Spring 上下文时为 null。 */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry registry) {
        if (registry != null) {
            registry.gauge("play.urlcache.hit", hit, AtomicInteger::get);
            registry.gauge("play.urlcache.miss", miss, AtomicInteger::get);
        }
    }

    /** 缓存键：userScope 缺省为 {@code anon}，platform 缺省为 {@code auto}（未显式指定平台）。 */
    static String key(String userScope, String platform, String sourceId) {
        String p = (platform == null || platform.isBlank())
                ? AUTO_PLATFORM : platform.toLowerCase(Locale.ROOT);
        String u = (userScope == null || userScope.isBlank()) ? ANON : userScope;
        return KEY_PREFIX + u + ":" + p + ":" + sourceId;
    }

    /** 命中返回条目（url + 换源平台），未命中返回 {@code null}。 */
    public Entry get(String userScope, String platform, String sourceId) {
        Map<String, Object> cached = cache.getAsMap(key(userScope, platform, sourceId));
        if (cached == null || cached.isEmpty()) {
            miss.incrementAndGet();
            return null;
        }
        Object url = cached.get("url");
        if (url == null || url.toString().isBlank()) {
            miss.incrementAndGet();
            return null;
        }
        hit.incrementAndGet();
        Object src = cached.get("src");
        return new Entry(url.toString(), src == null ? null : src.toString());
    }

    /** 只缓存非空 URL；{@code src} 为换源平台（未换源传 null），供 {@code X-Play-Source} 头。 */
    public void put(String userScope, String platform, String sourceId, String url, String src) {
        if (url == null || url.isBlank()) return;
        Map<String, Object> payload = new HashMap<>(2);
        payload.put("url", url);
        if (src != null && !src.isBlank()) payload.put("src", src);
        cache.set(key(userScope, platform, sourceId), payload, TTL);
    }

    /** 上游 403/404 时作废本条，让重试真正重新取链。 */
    public void invalidate(String userScope, String platform, String sourceId) {
        cache.delete(key(userScope, platform, sourceId));
        log.info("播放链接缓存作废: key={}", key(userScope, platform, sourceId));
    }

    public int getHitCount() { return hit.get(); }

    public int getMissCount() { return miss.get(); }

    /** 缓存条目：{@code src} 为换源命中的平台（未换源为 null）。 */
    public record Entry(String url, String src) { }
}
