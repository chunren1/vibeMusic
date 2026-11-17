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
    /** 取链结果：url + 音质档（"320"/"HQ"/"128"/"PQ"/"AAC"/"unknown"），换源排序用。 */
    record Fetched(String url, String quality) {
    }

    String tryKugouUrl(String hash) {
        return tryKugouUrlWithQuality(hash).url();
    }

    @SuppressWarnings("unchecked")
    Fetched tryKugouUrlWithQuality(String hash) {
        for (String level : new String[]{"standard", "low"}) {
            try {
                Map<String, Object> result = neteaseApiService.getKugouSongUrl(hash, null, level);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String url = (String) data.get(0).get("url");
                        if (url != null && !url.isEmpty()) {
                            // 2026-11-17：酷狗 standard 档并不保证 320k——实测《失眠》的
                            // standard 与 low 返回**同一份 128k 字节**。网关现在把"这一档
                            // 实际给的是 320 还是回落 128"写进 quality；拿不到才按档位猜。
                            Object q = data.get(0).get("quality");
                            return new Fetched(url, q instanceof String s && !s.isBlank()
                                    ? s : ("standard".equals(level) ? "unknown" : "128"));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("tryKugouUrl: {} level={} 失败: {}", ClientErrorScrubber.scrub(hash), level, ClientErrorScrubber.scrub(e.getMessage()), e);
            }
        }
        return new Fetched(null, "unknown");
    }

    /** 咪咕取链：musicapi 返回 302 签名 URL（IP-bound），由 StreamController 代理字节流；失败返回 null。 */
    /**
     * 咪咕取链：先请求 HQ（高品质），拿不到再回落 PQ。
     *
     * <p>换源场景下音质与"能不能播"同样重要：咪咕的 HQ 大多免费，先试一次的成本
     * 只是一次额外的上游请求（失败时多几百毫秒，仍在换源预算内）。</p>
     */
    String fetchMiguUrl(String sourceId) {
        return fetchMiguUrlWithQuality(sourceId).url();
    }

    @SuppressWarnings("unchecked")
    Fetched fetchMiguUrlWithQuality(String sourceId) {
        for (String tone : new String[]{"HQ", "PQ"}) {
            try {
                Map<String, Object> result = neteaseApiService.getMiguSongUrl(sourceId, null, tone);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String url = (String) data.get(0).get("url");
                        if (url != null && !url.isEmpty()) {
                            return new Fetched(url, "HQ".equals(tone) ? "HQ" : "PQ");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("fetchMiguUrl: {} tone={} 获取失败: {}", ClientScrubberSafe(sourceId), ClientErrorScrubber.scrub(e.getMessage()));
            }
        }
        return new Fetched(null, "unknown");
    }

    /** 日志脱敏的简写，避免上面那行过长。 */
    private static String ClientScrubberSafe(String value) {
        return ClientErrorScrubber.scrub(value);
    }

    /** B站：短音轨（AAC），音质记为 AAC 以便换源排序时排在高码率 MP3 之后。 */
    Fetched tryBiliUrlWithQuality(String biliId) {
        String url = tryBiliUrl(biliId);
        return new Fetched(url, url == null ? "unknown" : "AAC");
    }

    /**
     * QQ：码率可从文件名前缀解析（M800=320k、M500=128k、F000=FLAC），
     * 比"凭接口档位猜"可靠，直接用于换源排序。
     */
    Fetched fetchQqUrlWithQuality(String sourceId) {
        String url = fetchQqUrl(sourceId);
        return new Fetched(url, url == null ? "unknown" : qqQualityOf(url));
    }

    static String qqQualityOf(String url) {
        if (url == null) return "unknown";
        if (url.contains("/M800")) return "320";
        if (url.contains("/F000")) return "FLAC";
        if (url.contains("/M500")) return "128";
        return "unknown";
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
     *
     * <p>已知限制：对象名只含 {@code sourceId}，不含平台维度。五个平台的 ID 空间理论上可能
     * 重叠（网易云与 QQ 都是数字串），一旦重叠会互相命中对方的缓存文件。当前规模未观测到，
     * 根治方案是改成 {@code songs/<platform>/<sourceId>.mp3} 并做一次存量迁移——
     * 涉及存储布局变更，不宜在没有实际故障时顺手改。</p>
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