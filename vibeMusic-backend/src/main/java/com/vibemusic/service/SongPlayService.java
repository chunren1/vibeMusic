package com.vibemusic.service;

import com.vibemusic.config.AudioQualityTier;
import com.vibemusic.config.ThreadPoolConfig;
import com.vibemusic.common.utils.SongIdUtils;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.function.Supplier;

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

    /**
     * 降级计数接入 Prometheus（此前只写不读，属墓碑计数器）。
     * 可选注入：单元测试无 Spring 上下文时为 null，跳过注册。
     */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            meterRegistry.gauge("play.degradation.count", degradationCount, AtomicInteger::get);
        }
    }

    /** 当前用户的网易云 Cookie；解析逻辑收敛在 UserService（单一事实源）。 */
    private String resolveUserCookie() {
        return userService == null ? null : userService.resolveCurrentNeteaseCookie();
    }

    private Map<String, Object> fetchNeteaseUrl(String sourceId, String level, String userCookie, Long userId) {
        Map<String, Object> body = userCookie != null
                ? neteaseApiService.getSongUrl(sourceId, level, userCookie)
                : neteaseApiService.getSongUrl(sourceId, level);
        observeNeteaseUrlPayload(body, userCookie, userId);
        if (userCookie == null && NeteaseApiService.isNeedLoginPayload(body)) {
            try {
                if (neteaseApiService.rotateSharedCookie()) {
                    return neteaseApiService.getSongUrl(sourceId, level);
                }
            } catch (Exception e) {
                log.warn("共享 Cookie 快切重试失败（降级链不变）: {}", e.getMessage());
            }
        }
        return body;
    }

    /**
     * BYOC 过期探测（Q3-11 接线）：per-user 取链拿到上游 need-login 信号
     * （{@code /song/url/v1} 原样透传体，见 {@link NeteaseApiService#isNeedLoginPayload}）
     * 时将该用户 Cookie 标失效，后续 {@code GET /api/cookies/status} 返回
     * {@code needsRebind:true}。
     *
     * <p>仅 per-user 请求参与（userCookie/userId 任一缺席即跳过，匿名/共享失败永不翻转）；
     * 永不抛错，不干扰播放降级链；日志仅 userId + 上游 code，绝不记 Cookie 字节。
     * 搜索链路不接：网关 {@code /netease/search} 已归一化为 {@code code:200}，
     * 上游 need-login 在网关内即被吞为匿名降级，后端侧无信号可探。
     */
    private void observeNeteaseUrlPayload(Map<String, Object> body, String userCookie, Long userId) {
        if (userCookie == null || userId == null || userService == null) return;
        if (!NeteaseApiService.isNeedLoginPayload(body)) return;
        try {
            userService.markNeteaseCookieInvalid(userId, NeteaseApiService.extractUpstreamCode(body));
        } catch (Exception e) {
            log.warn("BYOC 失效标记失败: userId={}, error={}", userId, e.getMessage());
        }
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

    // ============ 限时调用/取消工具（getPlayUrl 共用）============

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

    /**
     * 在剩余预算内限时执行上游调用：预算约束调用本身而非仅约束是否发起。
     * 预算耗尽/超时/异常一律返回 null，由调用方按降级处理；超时时 cancel 排队任务。
     */
    private <T> T callWithDeadline(Supplier<T> s, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            log.warn("限时调用跳过: 预算已耗尽");
            return null;
        }
        final CompletableFuture<T> cf;
        try {
            cf = CompletableFuture.supplyAsync(s, getUrlExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("限时调用取链池过载，直接跳过");
            return null;
        }
        try {
            return cf.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cf.cancel(true);
            log.warn("限时调用超时: {}ms 预算内未返回，已取消", left);
            return null;
        } catch (InterruptedException e) {
            cf.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("限时调用被中断，已取消");
            return null;
        } catch (ExecutionException e) {
            log.warn("限时调用上游异常: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }
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
        // 请求线程捕获 userId（异步取链线程读不到 SecurityContext）。
        final Long callerUserId = UserService.getCurrentUserId();
        // 生产链路整体 8s 超时保护
        final long DEADLINE = System.currentTimeMillis() + 8000;
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
                String miguUrl = fetchMiguUrl(sourceId);
                if (miguUrl != null) return miguUrl;
                log.info("getPlayUrl: Migu歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitKugou || (!explicitQQ && !explicitNE && isKugouHash(sourceId))) {
                // 酷狗：platform=kugou 显式指定，或无显式平台时的 32 位 hash 猜测
                // （此前误入 QQ 分支）；albumId 暂无法透传，取链失败则走网易云降级
                String kugouUrl = tryKugouUrl(sourceId);
                if (kugouUrl != null) return kugouUrl;
                log.info("getPlayUrl: Kugou歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitBili || (!explicitQQ && !explicitNE && !explicitMigu && !explicitKugou && isBiliId(sourceId))) {
                // B站：platform=bilibili 显式指定，或无显式平台时的 BV 猜测
                // （BV 形绝不能落入 QQ 分支，故本分支置于 QQ 分支之前）
                String biliUrl = tryBiliUrl(sourceId);
                if (biliUrl != null) return biliUrl;
                log.info("getPlayUrl: Bili歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitQQ || (!explicitNE && !guessNetEase)) {
                // 取链实现与其它平台分支共用 fetchQqUrl（单一事实源）
                String qqUrl = fetchQqUrl(sourceId);
                if (qqUrl != null) return qqUrl;
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else {
                // 并行探测 3 级音质（exhigh/higher/standard），取最高可用非试听版本
                // 探测实现与其它平台分支共用 probeNeteaseParallel（单一事实源）
                String[] levels = {"exhigh", "higher", "standard"};
                NeteaseProbe probe = probeNeteaseParallel(sourceId, levels, DEADLINE, userCookie, callerUserId);
                if (probe.url != null) return probe.url;
                // 网易云无可用链接（全失败或全试听）→ QQ 降级（Q2d 收敛）
                String qqUrl = degradeNeteaseToQq(songName, artist, sourceId, DEADLINE);
                if (qqUrl != null) return qqUrl;
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

    /**
     * 网易云并行探测（生产唯一取链路径，单一事实源）。
     * 按 [levels] 优先级检查并行提交的取链结果：跳过试听片段，返回首个可用 URL。
     * 预算统一由 [deadline] 约束（生产路径原先各自硬编码 5s 等待，与 8s 预算脱节）。
     */
    @SuppressWarnings("unchecked")
    private NeteaseProbe probeNeteaseParallel(String sourceId, String[] levels, long deadline,
                                              String userCookie, Long callerUserId) {
        Map<String, CompletableFuture<Map<String, Object>>> futures = new LinkedHashMap<>();
        for (String level : levels) {
            if (System.currentTimeMillis() > deadline) break;
            try {
                final String uc = userCookie;
                final Long uid = callerUserId;
                futures.put(level, CompletableFuture.supplyAsync(
                        () -> fetchNeteaseUrl(sourceId, level, uc, uid), getUrlExecutor));
            } catch (RejectedExecutionException e) {
                log.warn("音质[{}] 歌曲 {} 取链池过载，直接跳过该级别", level, sourceId);
            }
        }
        Map<String, Object> standardProbe = null;
        int trialCount = 0;
        try {
            for (String level : levels) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("音质降级链超时: {}, 已检查至 {}", sourceId, level);
                    break;
                }
                CompletableFuture<Map<String, Object>> cf = futures.get(level);
                if (cf == null) continue;
                try {
                    long remaining = Math.max(1, deadline - System.currentTimeMillis());
                    Map<String, Object> result = cf.get(remaining, TimeUnit.MILLISECONDS);
                    if (result == null) continue;
                    if (AudioQualityTier.STANDARD.toNeteaseLevel().equals(level)) standardProbe = result;
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data == null || data.isEmpty()) continue;
                    String url = (String) data.get(0).get("url");
                    if (url == null || url.isEmpty()) continue;
                    Object trial = data.get(0).get("freeTrialInfo");
                    Object time = data.get(0).get("time");
                    if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) {
                        trialCount++;
                        log.info("音质[{}] 歌曲 {} 为试听片段 → 尝试下一级", level, sourceId);
                        continue;
                    }
                    return new NeteaseProbe(url, level, standardProbe, trialCount);
                } catch (TimeoutException e) {
                    log.info("音质[{}] 歌曲 {} 超时 → 尝试下一级", level, sourceId);
                } catch (Exception e) {
                    log.warn("音质[{}] 歌曲 {} 异常: {} → 尝试下一级", level, sourceId, e.getMessage());
                }
            }
        } finally {
            cancelUnfinished(futures.values());
        }
        return new NeteaseProbe(null, null, standardProbe, trialCount);
    }

    /** 网易云并行探测结果：命中 URL 与级别（可空）、standard 原始响应（试听兜底复用）、试听片段命中次数。 */
    private static final class NeteaseProbe {
        final String url;
        final String level;
        final Map<String, Object> standardProbe;
        final int trialCount;

        NeteaseProbe(String url, String level, Map<String, Object> standardProbe, int trialCount) {
            this.url = url;
            this.level = level;
            this.standardProbe = standardProbe;
            this.trialCount = trialCount;
        }
    }

    /** 咪咕取链：musicapi 返回 302 签名 URL（IP-bound），由 StreamController 代理字节流；失败返回 null。 */
    @SuppressWarnings("unchecked")
    private String fetchMiguUrl(String sourceId) {
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
            log.warn("fetchMiguUrl: {} 获取失败: {}", sourceId, e.getMessage());
        }
        return null;
    }

    /** QQ 直取链接（platform=qq 或 id 非纯数字猜测）；失败返回 null 由调用方降级，永不抛错。 */
    @SuppressWarnings("unchecked")
    private String fetchQqUrl(String sourceId) {
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
            log.warn("fetchQqUrl: {} 获取失败: {}", sourceId, e.getMessage());
        }
        return null;
    }

    /**
     * 网易→QQ 降级链唯一入口（Q2d 收敛）。
     *
     * <p>决策记录：网易云无可用链接（全失败或全试听）时统一经此步到 QQ，再落到 DB
     * 兜底；原先生产路径的 {@code neAllFailed} 门控只在取链超时才成立
     * （supplier 内异常被吞，flag 近似死代码），会导致全试听时跳过 QQ 直接落 DB，
     * 已删除。历史上的孪生元信息路径 getPlayInfo 已整体移除（零生产调用）。
     */
    private String degradeNeteaseToQq(String songName, String artist, String sourceId, long deadline) {
        degradationCount.incrementAndGet();
        log.info("音质降级: {} 网易云不可用 → 尝试QQ降级", sourceId);
        return callWithDeadline(() -> tryQQFallback(songName, artist, sourceId), deadline);
    }

    @SuppressWarnings("unchecked")
    private String tryQQFallback(String songName, String artist, String neteaseId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, neteaseId)
                    .select(Song::getName, Song::getArtist));
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
    private String tryNeteaseFallback(String songName, String artist, String qqSourceId, String userCookie, Long userId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, qqSourceId)
                    .select(Song::getName, Song::getArtist));
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
                Map<String, Object> urlResult = fetchNeteaseUrl(neteaseId, level, userCookie, userId);
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
