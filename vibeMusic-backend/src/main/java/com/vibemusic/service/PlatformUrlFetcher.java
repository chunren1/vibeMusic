package com.vibemusic.service;

import com.vibemusic.common.utils.ClientErrorScrubber;
import com.vibemusic.common.utils.SongIdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 平台直取层（从 SongPlayService 抽出，行为不变）：B站/酷狗/咪咕/QQ 的 guest 取链，
 * 以及 MinIO 缓存存在性判定（Redis 结果缓存）。
 *
 * <p>职责边界：只负责「向单个平台要一条 URL」并如实返回 null；
 * 平台选择、跨平台降级与 DB 兜底仍在 facade。所有方法都不抛异常。
 */
@Slf4j
class PlatformUrlFetcher {

    private static final String MINIO_CACHE_PREFIX = "minio:exists:v1:";
    private static final Duration MINIO_CACHE_TTL = Duration.ofMinutes(10);

    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;

    PlatformUrlFetcher(NeteaseApiService neteaseApiService,
                       StorageService storageService,
                       StringRedisTemplate stringRedisTemplate) {
        this.neteaseApiService = neteaseApiService;
        this.storageService = storageService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 酷狗 hash 判定：32 位十六进制（全数字 32 位亦命中，优先于网易云纯数字分支）。 */
    static boolean isKugouHash(String sourceId) {
        return SongIdUtils.isKugouHash(sourceId);
    }

    /**
     * B站 track 判定：纯 bvid(BV 开头)或复合 bvid|cid。
     * BV 形与 QQ songmid 字符集有交集，故调用方必须优先显式 platform，
     * ID 猜测仅在无显式平台时生效；裸数字 aid 一律不视为 B 站(归网易云)。
     */
    static boolean isBiliId(String sourceId) {
        return SongIdUtils.isBiliId(sourceId);
    }

    /**
     * B站 guest 取链：网关 /bili/url 直通（纯 bvid 或复合 bvid|cid 原样透传）。
     * 失败返回 null 由调用方降级，永不抛错。
     */
    @SuppressWarnings("unchecked")
    String tryBiliUrl(String biliId) {
        try {
            Map<String, Object> result = neteaseApiService.getBiliSongUrl(biliId);
            if (result != null) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                if (data != null && !data.isEmpty()) {
                    String url = (String) data.get(0).get("url");
                    if (url != null && !url.isEmpty()) return url;
                }
            }
        } catch (Exception e) {
            log.warn("tryBiliUrl: {} 失败: {}", ClientErrorScrubber.scrub(biliId), ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return null;
    }

    /**
     * 酷狗取链：phase 1 匿名 standard→low 逐级尝试。albumId 尚无透传字段，
     * 传 null（网关按空 album_id 处理）；失败返回 null 由调用方降级，永不抛错。
     */
    @SuppressWarnings("unchecked")
    String tryKugouUrl(String hash) {
        for (String level : new String[]{"standard", "low"}) {
            try {
                Map<String, Object> result = neteaseApiService.getKugouSongUrl(hash, null, level);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String url = (String) data.get(0).get("url");
                        if (url != null && !url.isEmpty()) return url;
                    }
                }
            } catch (Exception e) {
                log.warn("tryKugouUrl: {} level={} 失败: {}", ClientErrorScrubber.scrub(hash), level, ClientErrorScrubber.scrub(e.getMessage()), e);
            }
        }
        return null;
    }

    /** 咪咕取链：musicapi 返回 302 签名 URL（IP-bound），由 StreamController 代理字节流；失败返回 null。 */
    @SuppressWarnings("unchecked")
    String fetchMiguUrl(String sourceId) {
        try {
            Map<String, Object> result = neteaseApiService.getMiguSongUrl(sourceId, null);
            if (result != null) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                if (data != null && !data.isEmpty()) {
                    String url = (String) data.get(0).get("url");
                    if (url != null && !url.isEmpty()) return url;
                }
            }
        } catch (Exception e) {
            log.warn("fetchMiguUrl: {} 获取失败: {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return null;
    }

    /** QQ 直取链接（platform=qq 或 id 非纯数字猜测）；失败返回 null 由调用方降级，永不抛错。 */
    @SuppressWarnings("unchecked")
    String fetchQqUrl(String sourceId) {
        try {
            Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
            if (result != null) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                if (data != null && !data.isEmpty()) {
                    String url = (String) data.get(0).get("url");
                    if (url != null && !url.isEmpty()) return url;
                }
            }
        } catch (Exception e) {
            log.warn("fetchQqUrl: {} 获取失败: {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return null;
    }

    /**
     * 检查歌曲是否缓存于 MinIO，Redis 缓存结果减少 MinIO HTTP 调用
     * 任何异常（Redis/MinIO 不可用）均返回 false，调用方继续走 API/DB 降级
     */
    boolean isCachedInMinio(String sourceId, String userCookie) {
        if (sourceId == null) return false;
        if (userCookie != null) {
            try {
                return storageService.exists("songs/" + sourceId + ".mp3");
            } catch (Exception e) {
                log.debug("isCachedInMinio per-user direct probe failed for {}: {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()));
                return false;
            }
        }
        try {
            String cacheKey = MINIO_CACHE_PREFIX + sourceId;
            // 先查 Redis
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if ("1".equals(cached)) return true;
            if ("0".equals(cached)) return false;

            // Redis 未命中 → 查 MinIO
            boolean exists = storageService.exists("songs/" + sourceId + ".mp3");
            // 写入 Redis 缓存（存在 10min，不存在 30s）
            Duration ttl = exists ? MINIO_CACHE_TTL : Duration.ofSeconds(30);
            stringRedisTemplate.opsForValue().set(cacheKey, exists ? "1" : "0", ttl);
            return exists;
        } catch (Exception e) {
            log.debug("isCachedInMinio failed for {} (Redis/MinIO unavailable): {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()));
            return false;
        }
    }
}
