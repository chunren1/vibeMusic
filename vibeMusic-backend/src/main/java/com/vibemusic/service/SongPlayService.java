package com.vibemusic.service;

import com.vibemusic.config.AudioQualityTier;
import com.vibemusic.config.ThreadPoolConfig;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲播放服务
 * <p>
 * 音质降级链：MinIO 缓存 → 原平台 API（多级音质）→ 跨平台降级 → DB 历史 URL 兜底
 * <p>
 * 线程池由 Spring 托管（ThreadPoolConfig），避免 static ExecutorService 泄漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongPlayService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ThreadPoolTaskExecutor getUrlExecutor;

    private UserService userService;

    @Autowired(required = false)
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    private String resolveUserCookie() {
        try {
            Long userId = UserService.getCurrentUserId();
            if (userId == null || userService == null) return null;
            String cookie = userService.resolveNeteaseCookie(userId).orElse(null);
            return (cookie == null || cookie.isBlank()) ? null : cookie;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fetchNeteaseUrl(String sourceId, String level, String userCookie) {
        return userCookie != null
                ? neteaseApiService.getSongUrl(sourceId, level, userCookie)
                : neteaseApiService.getSongUrl(sourceId, level);
    }

    private Map<String, Object> fetchNeteaseSearch(String keyword, int limit, String userCookie) {
        return userCookie != null
                ? neteaseApiService.searchNetease(keyword, limit, userCookie)
                : neteaseApiService.searchNetease(keyword, limit);
    }

    private static final String MINIO_CACHE_PREFIX = "minio:exists:v1:";
    private static final Duration MINIO_CACHE_TTL = Duration.ofMinutes(10);
    /** 试听片段判定阈值（毫秒）：时长 ≤ 30 秒视为试听片段 */
    private static final int TRIAL_DURATION_MS = 30000;
    private final AtomicInteger degradationCount = new AtomicInteger(0);

    public int getDegradationCount() { return degradationCount.get(); }

    /** 酷狗 hash 判定：32 位十六进制（全数字 32 位亦命中，优先于网易云纯数字分支）。 */
    static boolean isKugouHash(String sourceId) {
        return sourceId != null && sourceId.matches("(?i)[a-f0-9]{32}");
    }

    /**
     * B站 track 判定：纯 bvid(BV 开头)或复合 bvid|cid。
     * BV 形与 QQ songmid 字符集有交集，故调用方必须优先显式 platform，
     * ID 猜测仅在无显式平台时生效；裸数字 aid 一律不视为 B 站(归网易云)。
     */
    static boolean isBiliId(String sourceId) {
        if (sourceId == null) return false;
        String first = sourceId.split("\\|", -1)[0];
        return first.matches("BV[a-zA-Z0-9]+");
    }

    // ==================== getPlayInfo ====================

    /**
     * 取消未完成的并行取链任务。
     * <p>
     * 语义说明（CompletableFuture.cancel(true) 无法中断已开始的任务，
     * boolean 参数对 CompletableFuture 无实质中断效果）：
     * <ul>
     *   <li>排队中尚未开始的任务：cancel 后不再执行，不消耗线程与上游配额；</li>
     *   <li>已开始执行的任务：无法被中断，会执行至返回/超时，但其结果会被调用方丢弃。</li>
     * </ul>
     * loser 并发被天然限制在 getUrl 池大小（3 线程 + 10 队列，AbortPolicy 快速失败）
     * 与整体 8s DEADLINE 之内，不会 wedge 线程池。
     */
    private static void cancelUnfinished(Collection<CompletableFuture<Map<String, Object>>> futures) {
        for (CompletableFuture<Map<String, Object>> f : futures) {
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId) {
        return getPlayInfo(sourceId, null, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId, String songName, String artist) {
        return getPlayInfo(sourceId, songName, artist, resolveUserCookie());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPlayInfo(String sourceId, String songName, String artist, String userCookie) {
        Map<String, Object> info = new HashMap<>();
        info.put("isTrial", false);
        info.put("platform", isBiliId(sourceId) ? "bilibili" : (isKugouHash(sourceId) ? "kugou" : (sourceId.matches("\\d+") ? "netease" : "qq")));

        // 1. 优先 MinIO 本地缓存（Redis 缓存 exists 结果，TTL 10min，减少 MinIO statObject 调用）
        // per-user 请求绕过共享 Redis exists 缓存（防 VIP 结果交叉），直探 MinIO 且不回写
        String minioObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInMinio(sourceId, userCookie)) {
            String directUrl = storageService.getDirectUrl(minioObjectName);
            info.put("url", directUrl);
            info.put("fromCache", true);
            info.put("quality", AudioQualityTier.LOCAL.name());
            info.put("qualityLabel", AudioQualityTier.LOCAL.getLabel());
            info.put("degraded", false);
            log.info("音质[LOCAL] 歌曲 {} 命中 MinIO 缓存", sourceId);
            return info;
        }

        // 2. 在线获取：按 SLA 等级逐级降级（整体 8s 超时保护）
        final long DEADLINE = System.currentTimeMillis() + 8000;
        AudioQualityTier achievedTier = AudioQualityTier.FALLBACK;
        boolean degraded = false;

        try {
            if (isBiliId(sourceId)) {
                // B站 → guest 取链（phase 1 匿名 DASH 伴音轨）
                String biliUrl = tryBiliUrl(sourceId);
                if (biliUrl != null) {
                    info.put("url", biliUrl);
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", false);
                    return info;
                }
                degradationCount.incrementAndGet();
                log.info("B站歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String biFallback = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (biFallback != null) {
                    info.put("url", biFallback);
                    info.put("platform", "netease");
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", true);
                    info.put("fallbackFrom", "bilibili-no-url");
                    return info;
                }
            } else if (isKugouHash(sourceId)) {
                // 酷狗 → 尝试获取 URL（phase 1 匿名：standard→low 逐级取链）
                String kugouUrl = tryKugouUrl(sourceId);
                if (kugouUrl != null) {
                    info.put("url", kugouUrl);
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", false);
                    return info;
                }
                degradationCount.incrementAndGet();
                log.info("酷狗歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String kgFallback = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (kgFallback != null) {
                    info.put("url", kgFallback);
                    info.put("platform", "netease");
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", true);
                    info.put("fallbackFrom", "kugou-no-url");
                    return info;
                }
            } else if (sourceId.matches("\\d+")) {
                // 网易云音质降级：并行探测所有级别，按质量优先级取首个非试听结果
                // 优化前（串行）：P95 4.41s — HIRES→EXHIGH→HIGHER→STANDARD 逐级等待，4次API累计
                // 优化后（并行）：所有级别同时请求，最快可用结果 = max(单次API耗时)，P95 目标 < 2s
                AudioQualityTier[] tiers = {
                    AudioQualityTier.HIRES, AudioQualityTier.EXHIGH,
                    AudioQualityTier.HIGHER, AudioQualityTier.STANDARD
                };
                // 并行提交所有音质级别请求（使用 Spring 托管线程池）。
                // getUrl 池为 AbortPolicy：过载时 submit 同步抛 RejectedExecutionException，
                // 该级别直接跳过（futures 中无条目，后续按 null 处理），不再阻塞降级链。
                Map<AudioQualityTier, CompletableFuture<Map<String, Object>>> futures = new HashMap<>();
                for (AudioQualityTier tier : tiers) {
                    if (System.currentTimeMillis() > DEADLINE) break;
                    try {
                        final String uc = userCookie;
                        futures.put(tier, CompletableFuture.supplyAsync(() ->
                                fetchNeteaseUrl(sourceId, tier.toNeteaseLevel(), uc), getUrlExecutor));
                    } catch (RejectedExecutionException e) {
                        log.warn("音质[{}] 歌曲 {} 取链池过载，直接跳过该级别", tier.getLabel(), sourceId);
                    }
                }
                // 按音质优先级顺序检查结果（已完成的高优级别立即返回，未完成的等待但不再串行累积）
                // 命中后 finally 取消排队中的 futures（已开始的任务无法中断，结果丢弃）；
                // standard 级探测结果会被缓存，供最终试听兜底复用，避免重复请求。
                Map<String, Object> standardProbe = null;
                try {
                for (AudioQualityTier tier : tiers) {
                    if (System.currentTimeMillis() > DEADLINE) {
                        log.warn("音质降级链超时: {}, 已检查至 {}", sourceId, tier.getLabel());
                        break;
                    }
                    CompletableFuture<Map<String, Object>> cf = futures.get(tier);
                    if (cf == null) continue;
                    try {
                        long remaining = Math.max(1, DEADLINE - System.currentTimeMillis());
                        Map<String, Object> result = cf.get(remaining, TimeUnit.MILLISECONDS);
                        if (result == null) continue;
                        if (tier == AudioQualityTier.STANDARD) standardProbe = result;
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data == null || data.isEmpty()) continue;
                        String url = (String) data.get(0).get("url");
                        if (url == null || url.isEmpty()) continue;
                        Object trial = data.get(0).get("freeTrialInfo");
                        Object time = data.get(0).get("time");
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) {
                            degradationCount.incrementAndGet();
                            degraded = true;
                            log.info("音质降级: {} [{}] 为试听片段 → 尝试下一级", sourceId, tier.getLabel());
                            continue;
                        }
                        achievedTier = tier;
                        info.put("url", url);
                        info.put("quality", achievedTier.name());
                        info.put("qualityLabel", achievedTier.getLabel());
                        info.put("degraded", degraded);
                        log.info("音质[{}] 歌曲 {} 在线获取成功{}",
                            achievedTier.getLabel(), sourceId, degraded ? " (经并行降级)" : "");
                        return info;
                    } catch (TimeoutException e) {
                        log.info("音质[{}] 歌曲 {} 超时 → 尝试下一级", tier.getLabel(), sourceId);
                    } catch (Exception e) {
                        log.warn("音质[{}] 歌曲 {} 异常: {} → 尝试下一级", tier.getLabel(), sourceId, e.getMessage());
                    }
                }
                } finally {
                    cancelUnfinished(futures.values());
                }
                // 网易云全部降级为试听 → QQ降级（超时则跳过）
                if (System.currentTimeMillis() > DEADLINE) {
                    log.warn("音质降级链超时: {} 跳过QQ降级, 降级至试听", sourceId);
                } else {
                    degradationCount.incrementAndGet();
                    log.info("音质降级: {} 网易云全试听 → 尝试QQ降级", sourceId);
                    String qqUrl = tryQQFallback(songName, artist, sourceId);
                    if (qqUrl != null) {
                        achievedTier = AudioQualityTier.HIGHER;
                        info.put("url", qqUrl);
                        info.put("platform", "qq");
                        info.put("quality", AudioQualityTier.HIGHER.name());
                        info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                        info.put("degraded", true);
                        info.put("fallbackFrom", "netease-trial");
                        return info;
                    }
                } // end of DEADLINE else block
                achievedTier = AudioQualityTier.FALLBACK;
                info.put("isTrial", true);
                info.put("quality", AudioQualityTier.FALLBACK.name());
                info.put("qualityLabel", AudioQualityTier.FALLBACK.getLabel());
                info.put("degraded", true);
                // 试听兜底：优先复用并行探测已拿到的 standard 结果，不再发起重复请求；
                // 探测无结果时仅在预算未耗尽时补发一次（同步调用最坏 +45s，不许超出 8s 预算）。
                Map<String, Object> f = standardProbe;
                if (f == null) {
                    long remaining = DEADLINE - System.currentTimeMillis();
                    if (remaining <= 0) {
                        log.warn("试听兜底跳过: {} 预算已耗尽，不再补发 standard 请求", sourceId);
                    } else {
                        try {
                            f = fetchNeteaseUrl(sourceId, "standard", userCookie);
                        } catch (Exception e) {
                            log.warn("试听兜底 standard 请求失败: {} - {}", sourceId, e.getMessage());
                            f = null;
                        }
                    }
                }
                if (f != null) {
                    List<Map<String, Object>> d = (List<Map<String, Object>>) f.get("data");
                    if (d != null && !d.isEmpty()) info.put("url", d.get(0).get("url"));
                }
            } else {
                // QQ 音乐 → 尝试获取 URL
                Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String qqUrl = (String) data.get(0).get("url");
                        if (qqUrl != null && !qqUrl.isEmpty()) {
                            info.put("url", qqUrl);
                            info.put("quality", AudioQualityTier.HIGHER.name());
                            info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                            info.put("degraded", false);
                            return info;
                        }
                    }
                }
                degradationCount.incrementAndGet();
                log.info("QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (neteaseUrl != null) {
                    info.put("url", neteaseUrl);
                    info.put("platform", "netease");
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", true);
                    info.put("fallbackFrom", "qq-no-url");
                    return info;
                }
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试 MinIO 兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 历史URL兜底（只查 url 列，避免读取 TEXT 歌词列）
        if (info.get("url") == null) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, sourceId)
                    .select(Song::getId, Song::getUrl));
            if (song != null && song.getUrl() != null) {
                info.put("url", song.getUrl());
                info.put("fromCache", true);
                info.put("quality", AudioQualityTier.STANDARD.name());
                info.put("qualityLabel", AudioQualityTier.STANDARD.getLabel());
                info.put("degraded", true);
                degradationCount.incrementAndGet();
                log.info("音质降级: {} API失败 → DB历史URL兜底", sourceId);
            }
        }

        if (!info.containsKey("quality")) {
            info.put("quality", AudioQualityTier.FALLBACK.name());
            info.put("qualityLabel", AudioQualityTier.FALLBACK.getLabel());
            info.put("degraded", true);
        }
        return info;
    }

    // ==================== getPlayUrl ====================

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
        return getPlayUrl(sourceId, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist) {
        return getPlayUrl(sourceId, songName, artist, null);
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist, String platform) {
        return getPlayUrl(sourceId, songName, artist, platform, resolveUserCookie());
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist, String platform, String userCookie) {
        // 1. 优先检查 MinIO 缓存（Redis 缓存 exists 结果，TTL 10min）
        String minioObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInMinio(sourceId, userCookie)) {
            String directUrl = storageService.getDirectUrl(minioObjectName);
            log.info("getPlayUrl: {} 命中 MinIO 缓存", sourceId);
            return directUrl;
        }

        // 2. 尝试从 API 获取
        boolean explicitQQ = "qq".equalsIgnoreCase(platform);
        boolean explicitNE = "netease".equalsIgnoreCase(platform);
        boolean explicitMigu = "migu".equalsIgnoreCase(platform);
        boolean explicitKugou = "kugou".equalsIgnoreCase(platform);
        boolean explicitBili = "bilibili".equalsIgnoreCase(platform);
        boolean guessNetEase = sourceId != null && sourceId.matches("\\d+");

        try {
            if (explicitMigu) {
                // 咪咕：musicapi 返回 302 Location 签名 URL（IP-bound），由 StreamController 代理字节流
                try {
                    Map<String, Object> result = neteaseApiService.getMiguSongUrl(sourceId, null);
                    if (result != null) {
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data != null && !data.isEmpty()) {
                            String miguUrl = (String) data.get(0).get("url");
                            if (miguUrl != null && !miguUrl.isEmpty()) return miguUrl;
                        }
                    }
                } catch (Exception e) {
                    log.warn("getPlayUrl: Migu {} 获取失败: {}", sourceId, e.getMessage());
                }
                log.info("getPlayUrl: Migu歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitKugou || (!explicitQQ && !explicitNE && isKugouHash(sourceId))) {
                // 酷狗：platform=kugou 显式指定，或无显式平台时的 32 位 hash 猜测
                // （此前误入 QQ 分支）；albumId 暂无法透传，取链失败则走网易云降级
                String kugouUrl = tryKugouUrl(sourceId);
                if (kugouUrl != null) return kugouUrl;
                log.info("getPlayUrl: Kugou歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitBili || (!explicitQQ && !explicitNE && !explicitMigu && !explicitKugou && isBiliId(sourceId))) {
                // B站：platform=bilibili 显式指定，或无显式平台时的 BV 猜测
                // （BV 形绝不能落入 QQ 分支，故本分支置于 QQ 分支之前）
                String biliUrl = tryBiliUrl(sourceId);
                if (biliUrl != null) return biliUrl;
                log.info("getPlayUrl: Bili歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitQQ || (!explicitNE && !guessNetEase)) {
                try {
                    Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                    if (result != null) {
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data != null && !data.isEmpty()) {
                            String qqUrl = (String) data.get(0).get("url");
                            if (qqUrl != null && !qqUrl.isEmpty()) return qqUrl;
                        }
                    }
                } catch (Exception e) {
                    log.warn("getPlayUrl: QQ {} 获取失败: {}", sourceId, e.getMessage());
                }
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie);
                if (neteaseUrl != null) return neteaseUrl;
            } else {
                // 并行探测 3 级音质（exhigh/higher/standard），取最高可用非试听版本
                String[] levels = {"exhigh", "higher", "standard"};
                List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
                for (String level : levels) {
                    try {
                        final String uc = userCookie;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            try {
                                return fetchNeteaseUrl(sourceId, level, uc);
                            } catch (Exception e) {
                                log.debug("getPlayUrl: 网易云 {} level={} 失败: {}", sourceId, level, e.getMessage());
                                return null;
                            }
                        }, getUrlExecutor));
                    } catch (RejectedExecutionException e) {
                        log.warn("getPlayUrl: 取链池过载，level={} 直接跳过", level);
                        futures.add(CompletableFuture.completedFuture(null));
                    }
                }
                String neUrl = null;
                boolean neAllFailed = true;
                try {
                for (int i = 0; i < levels.length && neUrl == null; i++) {
                    try {
                        Map<String, Object> result = futures.get(i).get(5, TimeUnit.SECONDS);
                        neAllFailed = false;
                        if (result == null) continue;
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data == null || data.isEmpty()) continue;
                        String url = (String) data.get(0).get("url");
                        if (url == null || url.isEmpty()) continue;
                        Object trial = data.get(0).get("freeTrialInfo");
                        Object time = data.get(0).get("time");
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) {
                            log.info("歌曲 {} 音质 {} 为试听, 尝试降级", sourceId, levels[i]);
                            continue;
                        }
                        neUrl = url;
                    } catch (Exception e) {
                        log.debug("getPlayUrl: level={} timeout/error: {}", levels[i], e.getMessage());
                    }
                }
                } finally {
                    cancelUnfinished(futures);
                }
                if (neUrl != null) return neUrl;
                if (neAllFailed) {
                    log.info("getPlayUrl: 歌曲 {} 网易云全失败，尝试QQ降级", sourceId);
                    String qqUrl = tryQQFallback(songName, artist, sourceId);
                    if (qqUrl != null) return qqUrl;
                }
                log.warn("歌曲 {} 所有平台均无可用播放链接", sourceId);
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试DB兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 兜底（只查 url 列）
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                .eq(Song::getSourceId, sourceId)
                .select(Song::getId, Song::getUrl));
        return song != null ? song.getUrl() : null;
    }

    // ==================== 跨平台降级 ====================

    /**
     * 从搜索结果中找最优匹配，基于词重叠率 + 歌名首字匹配
     */
    private Map<String, Object> findBestMatch(List<Map<String, Object>> results, String expectedName, String expectedArtist) {
        Map<String, Object> best = null;
        double bestScore = 0;
        String target = (expectedName + " " + (expectedArtist != null ? expectedArtist : "")).toLowerCase().trim();
        for (Map<String, Object> item : results) {
            String candName = item.get("name") != null ? String.valueOf(item.get("name")) : "";
            String candArtist = item.get("artists") != null ? String.valueOf(item.get("artists")) : "";
            String candidate = (candName + " " + candArtist).toLowerCase().trim();
            double score = similarity(target, candidate);
            // 歌名首字相同加分
            if (!candName.isEmpty() && !expectedName.isEmpty() && candName.charAt(0) == expectedName.charAt(0)) score += 0.2;
            if (score > bestScore) { bestScore = score; best = item; }
        }
        return bestScore > 0.15 ? best : (results.get(0)); // 分数太低降级取第一条
    }

    /** 简单 token 重叠率 */
    private double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("\\s+")));
        int overlap = 0;
        for (String s : setA) { if (setB.contains(s)) overlap++; }
        double jaccard = (double) overlap / Math.max(setA.size(), setB.size());
        // 包含关系 bonus
        if (a.contains(b) || b.contains(a)) return Math.max(jaccard, 0.6);
        return jaccard;
    }

    // ==================== 跨平台降级（续） ====================

    /**
     * B站 guest 取链：网关 /bili/url 直通（纯 bvid 或复合 bvid|cid 原样透传）。
     * 失败返回 null 由调用方降级，永不抛错。
     */
    @SuppressWarnings("unchecked")
    private String tryBiliUrl(String biliId) {
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
            log.warn("tryBiliUrl: {} 失败: {}", biliId, e.getMessage());
        }
        return null;
    }

    /**
     * 酷狗取链：phase 1 匿名 standard→low 逐级尝试。albumId 尚无透传字段，
     * 传 null（网关按空 album_id 处理）；失败返回 null 由调用方降级，永不抛错。
     */
    @SuppressWarnings("unchecked")
    private String tryKugouUrl(String hash) {        for (String level : new String[]{"standard", "low"}) {
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
                log.warn("tryKugouUrl: {} level={} 失败: {}", hash, level, e.getMessage());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String tryQQFallback(String songName, String artist, String neteaseId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, neteaseId));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseApiService.searchQQ(keyword, 5);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            // 歌名/歌手相似度匹配，避免取到错误歌曲
            Map<String, Object> best = findBestMatch(data, songName, artist);
            if (best == null) { log.info("QQ降级: '{}' 无匹配歌曲", keyword); return null; }
            String qqSourceId = best.get("id") != null ? String.valueOf(best.get("id")) : null;
            String matchName = best.get("name") != null ? String.valueOf(best.get("name")) : "";
            String matchArtists = best.get("artists") != null ? String.valueOf(best.get("artists")) : "";
            if (qqSourceId == null) return null;
            Object durObj = best.get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= TRIAL_DURATION_MS) {
                log.info("QQ降级: '{}' 也只有试听版，跳过", matchName);
                return null;
            }
            Map<String, Object> urlResult = neteaseApiService.getQQSongUrl(qqSourceId);
            if (urlResult != null) {
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData != null && !urlData.isEmpty()) {
                    String url = (String) urlData.get(0).get("url");
                    if (url != null && !url.isEmpty()) {
                        log.info("QQ降级成功: '{}' → QQ '{}' (score={})", keyword, matchName, similarity(keyword, matchName + matchArtists));
                        return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }

    /**
     * 检查歌曲是否缓存于 MinIO，Redis 缓存结果减少 MinIO HTTP 调用
     * 任何异常（Redis/MinIO 不可用）均返回 false，调用方继续走 API/DB 降级
     */
    private boolean isCachedInMinio(String sourceId) {
        return isCachedInMinio(sourceId, null);
    }

    private boolean isCachedInMinio(String sourceId, String userCookie) {
        if (sourceId == null) return false;
        if (userCookie != null) {
            try {
                return storageService.exists("songs/" + sourceId + ".mp3");
            } catch (Exception e) {
                log.debug("isCachedInMinio per-user direct probe failed for {}: {}", sourceId, e.getMessage());
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
            log.debug("isCachedInMinio failed for {} (Redis/MinIO unavailable): {}", sourceId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String tryNeteaseFallback(String songName, String artist, String qqSourceId, String userCookie) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, qqSourceId));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = fetchNeteaseSearch(keyword, 5, userCookie);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            String neteaseId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (neteaseId == null) return null;
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= TRIAL_DURATION_MS) {
                log.info("网易云降级: {} 也只有试听版，跳过", keyword);
                return null;
            }
            String[] levels = {"exhigh", "higher", "standard"};
            for (String level : levels) {
                Map<String, Object> urlResult = fetchNeteaseUrl(neteaseId, level, userCookie);
                if (urlResult == null) continue;
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData == null || urlData.isEmpty()) continue;
                String url = (String) urlData.get(0).get("url");
                if (url == null || url.isEmpty()) continue;
                Object trial = urlData.get(0).get("freeTrialInfo");
                Object time = urlData.get(0).get("time");
                if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) continue;
                log.info("网易云降级成功: {} → neteaseId={}, level={}", keyword, neteaseId, level);
                return url;
            }
            log.info("网易云降级: {} 所有音质均为试听，跳过", keyword);
        } catch (Exception e) {
            log.warn("网易云降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }
}
