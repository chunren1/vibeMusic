package com.vibemusic.service;

import com.vibemusic.config.ThreadPoolConfig;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 歌曲搜索服务
 * <p>
 * 三级缓存：Redis → ES → API 实时搜索
 * 支持分源搜索（网易云 / QQ）和跨平台去重合并
 * <p>
 * 线程池由 Spring 托管（ThreadPoolConfig），避免 static ExecutorService 泄漏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongSearchService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;
    private final ESSearchService esSearchService;
    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskExecutor searchExecutor;
    private final ThreadPoolTaskExecutor warmExecutor;
    private final StringRedisTemplate stringRedisTemplate;

    // 缓存命中率仪表盘
    private Counter redisHitCounter;
    private Counter esHitCounter;
    private Counter apiCallCounter;
    private Counter searchRejectedCounter;
    private Counter upstreamAllFailedCounter;
    private Timer searchTimer;

    @PostConstruct
    void initMetrics() {
        redisHitCounter = Counter.builder("cache.hit.redis")
                .description("Redis 缓存命中次数").register(meterRegistry);
        esHitCounter = Counter.builder("cache.hit.es")
                .description("ES 缓存命中次数").register(meterRegistry);
        apiCallCounter = Counter.builder("cache.miss.api")
                .description("穿透到 musicapi 的次数").register(meterRegistry);
        searchRejectedCounter = Counter.builder("search.pool.rejected")
                .description("搜索线程池过载拒绝次数").register(meterRegistry);
        upstreamAllFailedCounter = Counter.builder("search.upstream.all_failed")
                .description("三平台上游全部失败次数").register(meterRegistry);
        searchTimer = Timer.builder("search.latency")
                .description("搜索总耗时").register(meterRegistry);
    }

    // ==================== 热门关键词预热 ====================

    private static final List<String> HOT_KEYWORDS = List.of(
            "周杰伦", "晴天", "陈奕迅", "告白气球",
            "稻香", "夜曲", "七里香", "热歌"
    );

    /**
     * 启动时异步预热热门搜索词到 Redis + ES 缓存，
     * 避免上线后的首次 API 穿透造成 P95 延迟飙升。
     * 每个关键词间隔 1.5 秒防止同时击穿第三方 API。
     */
    @PostConstruct
    void preWarmHotKeywords() {
        warmExecutor.submit(() -> {
            log.info("[PREWARM] 开始预热 {} 个热门搜索关键词 ...", HOT_KEYWORDS.size());
            for (String kw : HOT_KEYWORDS) {
                try {
                    search(kw, 1, 20);
                    log.info("[PREWARM] '{}' 预热完成 (缓存已回写 Redis + ES)", kw);
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[PREWARM] '{}' 预热失败: {}", kw, e.getMessage());
                }
            }
            log.info("[PREWARM] 热门搜索关键词预热全部完成");
        });
    }

    private static final double NET_WEIGHT = 1.5;
    private static final double MIGU_WEIGHT = 1.4;
    private static final double QQ_WEIGHT = 0.6;
    private static final double CROSS_PLATFORM_BONUS = 0.3;
    // 查询相关性加分叠加在平台分之上，不改变平台权重与跨平台加分。
    private static final double RELEVANCE_EXACT_NAME_BONUS = 2.0;
    private static final double RELEVANCE_NAME_CONTAINS_BONUS = 1.0;
    private static final double RELEVANCE_ARTIST_BONUS = 1.0;
    private static final double RELEVANCE_NAME_AND_ARTIST_BONUS = 1.0;
    private static final int PER_PLATFORM_FETCH = 40;
    private static final int SEARCH_TIMEOUT_SEC = 4;
    /** 搜索 keyword 最大长度：超长截断，防上游/缓存键膨胀（与 size<=100 同属入参校验层）。 */
    public static final int MAX_KEYWORD_LENGTH = 200;
    /** 随机推荐 count 上限：直通 DB LIMIT（含 lyric TEXT），必须封顶。 */
    public static final int MAX_RANDOM_COUNT = 50;
    // 非 VIP 加权：用户侧无 VIP，适度降权付费内容（见 applyNonVipBonus 注释）。
    static final double NON_VIP_BONUS = 0.5;
    // 未获单飞锁时的 bounded 短轮询：持锁者仍在搜索中，立即重读必 miss；
    // 短暂等待让持锁者回写缓存，并发同关键词请求共享结果而非各自穿透上游。
    // 上限 5 x 80ms = 400ms，远小于 SEARCH_TIMEOUT_SEC=4s 预算，不拖慢兜底路径。
    private static final int LOCK_WAIT_ROUNDS = 5;
    private static final long LOCK_WAIT_SLEEP_MS = 80;
    // QQ 熔断器阈值：连续失败/超时达此次数开路；开路时长 5 分钟。
    static final int QQ_BREAKER_FAILURE_THRESHOLD = 3;
    static final long QQ_BREAKER_OPEN_MS = 5 * 60 * 1000L;
    /**
     * QQ 熔断器 Redis 持久化键（Hash）：failures=连续失败计数，openedAt=开路时刻毫秒（-1=闭路）。
     * TTL 恒等于开路窗口；键过期即视为闭路（与内存语义一致：窗口过后进入半开探针）。
     * 半开探针占用标记仅保存在进程内（qqHalfOpenProbeInFlight + qqBreakerLock），跨实例重复探针无害（恰好计数一次）。
     */
    static final String QQ_BREAKER_REDIS_KEY = "circuit:qq:v1";
    private static final String QQ_BREAKER_F_FAILURES = "failures";
    private static final String QQ_BREAKER_F_OPENED_AT = "openedAt";
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

    // ======== QQ 熔断器状态（进程内内存；单实例部署假设，多实例需 Redis 共享，此处从简） ========
    // service 为单例，状态变更统一在 qqBreakerLock 下，计数器用 AtomicInteger，时钟 volatile 可注入（测试用）。
    private final AtomicInteger qqConsecutiveFailures = new AtomicInteger(0);
    private volatile long qqCircuitOpenedAt = -1L; // -1 = 闭路
    private volatile boolean qqHalfOpenProbeInFlight = false;
    private final Object qqBreakerLock = new Object();
    private volatile LongSupplier qqClock = System::currentTimeMillis;

    /** 测试用时钟注入（生产恒为 System::currentTimeMillis）。 */
    void setQqClockForTest(LongSupplier clock) {
        this.qqClock = clock;
    }

    @PreDestroy
    public void shutdown() {
        // Spring 会自动关闭 ThreadPoolTaskExecutor，这里仅记录日志
        log.info("[THREAD-POOL] SongSearchService 关闭，线程池由 Spring 生命周期管理");
    }

    @SuppressWarnings("unchecked")
    public SearchResult search(String keyword, int page, int size) {
        return search(keyword, page, size, null);
    }

    /**
     * 搜索歌曲（支持分源）
     * @param platform 可选: "netease", "qq", null=全部
     * @return SearchResult 包含分页信息 + 命中层级
     */
    @SuppressWarnings("unchecked")
    public SearchResult search(String keyword, int page, int size, String platform) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));
        if (keyword == null || keyword.trim().isEmpty())
            return SearchResult.of(Collections.emptyList(), 0, page, size, "none");
        String rawKw = keyword.trim();
        if (rawKw.length() > MAX_KEYWORD_LENGTH) rawKw = rawKw.substring(0, MAX_KEYWORD_LENGTH);
        final String kw = rawKw;
        final boolean searchBoth = (platform == null || platform.trim().isEmpty());
        final long searchStart = System.currentTimeMillis();

        String cacheExtra = searchBoth ? "all" : platform.trim().toLowerCase();

        // ======== 第 1 步：Redis 缓存（null=未命中；空 List=负缓存哨兵命中，直接返回空） ========
        long redisStart = System.currentTimeMillis();
        List<SongDTO> cached = cacheService.getSearchCache(kw + ":" + cacheExtra);
        if (cached != null) {
            redisHitCounter.increment();
            searchTimer.record(System.currentTimeMillis() - searchStart, TimeUnit.MILLISECONDS);
            long redisCost = System.currentTimeMillis() - redisStart;
            log.info("[CACHE-LAYER] Redis 命中: keyword='{}', page={}, totalCost={}ms",
                    kw, page, redisCost);
            int from = (page - 1) * size;
            int to = Math.min(from + size, cached.size());
            if (from >= cached.size()) return SearchResult.of(Collections.emptyList(), cached.size(), page, size, "redis");
            return SearchResult.of(cached.subList(from, to), cached.size(), page, size, "redis");
        }
        long redisCost = System.currentTimeMillis() - redisStart;
        log.info("[CACHE-LAYER] Redis 未命中: keyword='{}', page={}, cost={}ms", kw, page, redisCost);

        // ======== 第 2 步：ES 缓存（仅 :all 模式） ========
        if (searchBoth) {
            long esStart = System.currentTimeMillis();
            List<SongDTO> esCached;
            try {
                esCached = esSearchService.findByKeyword(kw);
            } catch (Exception e) {
                log.warn("[ES-LAYER] ES 查询异常，降级到 API: keyword='{}', error={}", kw, e.getMessage());
                esCached = List.of();
            }
            if (!esCached.isEmpty()) {
                esHitCounter.increment();
                searchTimer.record(System.currentTimeMillis() - searchStart, TimeUnit.MILLISECONDS);
                long esCost = System.currentTimeMillis() - esStart;
                log.info("[ES-LAYER] 返回ES缓存结果: keyword='{}', count={}, ES-cost={}ms, totalCost={}ms",
                        kw, esCached.size(), esCost, System.currentTimeMillis() - searchStart);
                cacheService.setSearchCache(kw + ":all", esCached, true, false);
                int from = (page - 1) * size;
                int to = Math.min(from + size, esCached.size());
                if (from >= esCached.size()) return SearchResult.of(Collections.emptyList(), esCached.size(), page, size, "es");
                return SearchResult.of(esCached.subList(from, to), esCached.size(), page, size, "es");
            }
        }

        // ======== 第 3 步：API 实时搜索（兜底）======
        // 分布式锁单飞：持锁者执行搜索并回写缓存；未获锁者短暂等待后重试读缓存，
        // 仍无缓存则兜底直接执行（锁持有者异常/过慢时不阻塞请求）
        String allCacheKey = kw + ":" + cacheExtra;
        String lockValue = cacheService.tryLock(allCacheKey);
        List<SongDTO> resultList;
        if (lockValue == null) {
            List<SongDTO> retried = null;
            for (int round = 0; round < LOCK_WAIT_ROUNDS; round++) {
                try {
                    Thread.sleep(LOCK_WAIT_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                retried = cacheService.getSearchCache(allCacheKey);
                if (retried != null) {
                    log.info("[CACHE-LAYER] 等待后命中单飞结果: keyword='{}', round={}", kw, round + 1);
                    int from = (page - 1) * size;
                    int to = Math.min(from + size, retried.size());
                    if (from >= retried.size()) return SearchResult.of(Collections.emptyList(), retried.size(), page, size, "redis");
                    return SearchResult.of(retried.subList(from, to), retried.size(), page, size, "redis");
                }
            }
            log.info("[API-LAYER] 单飞等待超时仍无缓存，兜底直查: keyword='{}', 原因=持锁者未及时回写", kw);
            resultList = doApiSearch(kw, cacheExtra, allCacheKey);
        } else {
            try {
                resultList = doApiSearch(kw, cacheExtra, allCacheKey);
            } finally {
                cacheService.releaseLock(allCacheKey, lockValue);
            }
        }

        // 处理分页返回
        int from = (page - 1) * size;
        int to = Math.min(from + size, resultList.size());
        if (from >= resultList.size())
            return SearchResult.of(Collections.emptyList(), resultList.size(), page, size, "api");
        searchTimer.record(System.currentTimeMillis() - searchStart, TimeUnit.MILLISECONDS);
        return SearchResult.of(resultList.subList(from, to), resultList.size(), page, size, "api");
    }

    /**
     * 执行第三方 API 搜索（单平台或双平台合并去重），并回写 Redis + ES 缓存。
     * 调用方负责单飞锁的获取与释放。
     */
    private List<SongDTO> doApiSearch(String kw, String cacheExtra, String allCacheKey) {
        apiCallCounter.increment();
        log.info("[API-LAYER] 触发实时搜索(单飞): keyword='{}', 原因=Redis和ES均未命中", kw);
        long apiStart = System.currentTimeMillis();

        if ("netease".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchNetease(kw));
            for (SongDTO s : songs) s.setPlatform("netease");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":netease", songs, true);
            return songs;
        }
        if ("qq".equals(cacheExtra)) {
            if (shouldSkipQq()) {
                log.warn("[QQ-BREAKER] 熔断中跳过QQ: keyword='{}'", kw);
                return Collections.emptyList();
            }
            List<SongDTO> songs = new ArrayList<>(safeSearchQQ(kw));
            for (SongDTO s : songs) s.setPlatform("qq");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":qq", songs, true);
            return songs;
        }
        if ("migu".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchMigu(kw));
            for (SongDTO s : songs) s.setPlatform("migu");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":migu", songs, true);
            return songs;
        }

        // 合并搜索（复用 Spring 托管线程池）；熔断开路时跳过 QQ 分支，不再吃 4s 超时。
        // 线程池过载 submit 同步抛 RejectedExecutionException → 快速失败转可重试错误，不静默丢任务。
        final AtomicBoolean neFailed = new AtomicBoolean(false);
        final AtomicBoolean qqFailed = new AtomicBoolean(false);
        final AtomicBoolean mgFailed = new AtomicBoolean(false);
        final Future<List<SongDTO>> neF;
        final Future<List<SongDTO>> qqF;
        final Future<List<SongDTO>> mgF;
        final AtomicBoolean qqOutcomeClaimed = new AtomicBoolean(false);
        try {
            neF = searchExecutor.submit(() -> safeSearchNetease(kw, neFailed));
            Future<List<SongDTO>> qqFuture = null;
            if (shouldSkipQq()) {
                log.warn("[QQ-BREAKER] 熔断中跳过QQ: keyword='{}'", kw);
            } else {
                qqFuture = searchExecutor.submit(() -> {
                    try {
                        List<SongDTO> songs = fetchQQ(kw);
                        recordQqOutcomeOnce(qqOutcomeClaimed, true);
                        return songs;
                    } catch (Exception e) {
                        recordQqOutcomeOnce(qqOutcomeClaimed, false);
                        qqFailed.set(true);
                        log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
                        return Collections.emptyList();
                    }
                });
            }
            qqF = qqFuture;
            mgF = searchExecutor.submit(() -> safeSearchMigu(kw, mgFailed));
        } catch (RejectedExecutionException rex) {
            // ThreadPoolTaskExecutor.submit 把拒绝包装为 TaskRejectedException（仍是 RejectedExecutionException 子类），此处一并捕获。
            searchRejectedCounter.increment();
            log.warn("[SEARCH-POOL] 搜索线程池过载，快速失败: keyword='{}', error={}", kw, rex.getMessage());
            throw new BusinessException(503, "搜索服务繁忙，请稍后重试");
        }
        List<SongDTO> neteaseSongs = getWithTimeout(neF, SEARCH_TIMEOUT_SEC, "Netease", neFailed);
        List<SongDTO> qqSongs = (qqF == null) ? Collections.emptyList() : getQqWithTimeout(qqF, qqOutcomeClaimed, qqFailed);
        List<SongDTO> miguSongs = getWithTimeout(mgF, SEARCH_TIMEOUT_SEC, "Migu", mgFailed);
        List<SongDTO> merged = mergePlatformResults(neteaseSongs, qqSongs, miguSongs, kw);

        boolean incomplete = neteaseSongs.isEmpty() || qqSongs.isEmpty();
        log.info("[API-LAYER] 搜索完成: keyword='{}', 网易云={}首, QQ={}首, 咪咕={}首, 去重后={}首, API-cost={}ms, totalCost={}ms",
                kw, neteaseSongs.size(), qqSongs.size(), miguSongs.size(), merged.size(),
                System.currentTimeMillis() - apiStart, System.currentTimeMillis());
        if (merged.isEmpty() && allUpstreamFailed(qqF == null, neFailed, qqFailed, mgFailed)) {
            upstreamAllFailedCounter.increment();
            log.warn("[API-LAYER] 三平台上游全部失败，不写空哨兵: keyword='{}'", kw);
            return merged;
        }
        cacheService.setSearchCache(allCacheKey, merged, !merged.isEmpty(), incomplete);
        if (!merged.isEmpty()) {
            esSearchService.indexSearchResults(kw, merged);
        }
        return merged;
    }

    /** 合并三平台结果：归一化去重、跨平台加分、查询相关性加分、按最终分排序 */
    private List<SongDTO> mergePlatformResults(List<SongDTO> neteaseSongs, List<SongDTO> qqSongs,
                                               List<SongDTO> miguSongs, String keyword) {
        Map<String, SongDTO> mergedMap = new LinkedHashMap<>();

        addPlatformSongs(mergedMap, neteaseSongs, NET_WEIGHT, "netease", false);
        addPlatformSongs(mergedMap, qqSongs, QQ_WEIGHT, "qq", true);
        addPlatformSongs(mergedMap, miguSongs, MIGU_WEIGHT, "migu", true);

        List<SongDTO> merged = new ArrayList<>(mergedMap.values());
        long relevanceStart = System.currentTimeMillis();
        applyRelevanceBonus(merged, keyword);
        applyNonVipBonus(merged);
        log.info("[SEARCH-RANK] 相关性重排: keyword='{}', count={}, relevance-cost={}ms",
                keyword, merged.size(), System.currentTimeMillis() - relevanceStart);
        merged.sort((a, b) -> Double.compare(
                b.getFinalScore() != null ? b.getFinalScore() : 0,
                a.getFinalScore() != null ? a.getFinalScore() : 0));
        return merged;
    }

    private void addPlatformSongs(Map<String, SongDTO> mergedMap, List<SongDTO> songs,
                                  double weight, String platform, boolean mergeOnDuplicate) {
        for (int i = 0; i < songs.size(); i++) {
            SongDTO song = songs.get(i);
            song.setPlatform(platform);
            song.setAvailableSources(new ArrayList<>(List.of(platform)));
            song.setFinalScore(computeScore(i + 1, weight));
            String key = normalizeKey(song.getName(), song.getArtist());
            SongDTO existing = mergedMap.get(key);

            if (existing != null && mergeOnDuplicate) {
                List<String> allSources = new ArrayList<>(existing.getAvailableSources());
                if (!allSources.contains(platform)) allSources.add(platform);
                SongDTO winner = pickBest(existing, song);
                winner.setAvailableSources(allSources);
                winner.setFinalScore(Math.max(existing.getFinalScore(), song.getFinalScore()) + CROSS_PLATFORM_BONUS);
                mergedMap.put(key, winner);
            } else {
                mergedMap.put(key, song);
            }
        }
    }

    public SearchResult search(String keyword) {
        return search(keyword, 1, 20);
    }

    public List<SongDTO> getRandomSongs(int count) {
        count = Math.max(1, Math.min(count, MAX_RANDOM_COUNT));
        List<SongDTO> songs;
        try {
            songs = new ArrayList<>(search("热歌", 1, 30, null).getList());
        } catch (Exception e) {
            log.warn("[RANDOM] 搜索热歌失败，降级到 DB 随机: {}", e.getMessage());
            songs = new ArrayList<>();
        }
        songs = songs.stream()
                .filter(s -> s.getSourceId() != null && !s.getSourceId().isEmpty())
                .filter(s -> s.getDuration() == null || s.getDuration() > 30)
                .collect(Collectors.toList());
        Collections.shuffle(songs);
        if (songs.size() > count) return songs.subList(0, count);
        if (songs.size() < count) {
            int need = count - songs.size();
            List<Song> dbSongs;
            try {
                dbSongs = songMapper.findRandomSongs(need);
            } catch (Exception e) {
                log.warn("[RANDOM] DB 随机查询失败，降级返回空: {}", e.getMessage());
                return songs; // 降级：返回已有 API 结果，不抛 500/503
            }
            if (dbSongs.size() < need) {
                // 随机起点后不足 N 首 → 从头补足
                try {
                    List<Song> head = songMapper.findFirstSongs(need - dbSongs.size());
                    dbSongs = new ArrayList<>(dbSongs);
                    dbSongs.addAll(head);
                } catch (Exception e) {
                    log.warn("[RANDOM] DB 补偿查询失败，返回已有结果: {}", e.getMessage());
                }
            }
            List<SongDTO> dbDtos = dbSongs.stream().map(s -> SongDTO.builder()
                    .sourceId(s.getSourceId()).name(s.getName()).artist(s.getArtist())
                    .album(s.getAlbum()).coverUrl(s.getCoverUrl() != null ? s.getCoverUrl().replace("http://", "https://") : null).duration(s.getDuration()).build()
            ).collect(Collectors.toList());
            songs.addAll(dbDtos);
        }
        return songs;
    }

    @Cacheable(value = "songTotalCount", sync = true)
    public long getTotalSongCount() {
        return songMapper.selectCount(null);
    }

    // ==================== 私有辅助方法 ====================

    private double computeScore(int rank, double platformWeight) {
        return (1.0 / rank) * platformWeight;
    }

    private SongDTO pickBest(SongDTO a, SongDTO b) {
        boolean aTrial = a.getDuration() != null && a.getDuration() <= 30;
        boolean bTrial = b.getDuration() != null && b.getDuration() <= 30;
        if (aTrial && !bTrial) return b;
        if (!aTrial && bTrial) return a;
        double aQ = qualityScore(a);
        double bQ = qualityScore(b);
        if (aQ != bQ) return aQ > bQ ? a : b;
        double aS = a.getFinalScore() != null ? a.getFinalScore() : 0;
        double bS = b.getFinalScore() != null ? b.getFinalScore() : 0;
        return aS >= bS ? a : b;
    }

    private double qualityScore(SongDTO song) {
        double score = 0;
        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) score += 0.5;
        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) score += 0.5;
        return score;
    }

    private String normalizeKey(String name, String artist) {
        return normalizeText((name != null ? name : "") + "|" + (artist != null ? artist : ""));
    }

    private String normalizeText(String s) {
        if (s == null) return "";
        return NON_ALPHANUM.matcher(s.toLowerCase().replaceAll("\\s+", "")).replaceAll("");
    }

    private List<String> tokenizeQuery(String keyword) {
        if (keyword == null) return List.of();
        return Arrays.stream(keyword.trim().split("\\s+"))
                .map(this::normalizeText)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    private void applyRelevanceBonus(List<SongDTO> merged, String keyword) {
        if (merged.isEmpty()) return;
        List<String> tokens = tokenizeQuery(keyword);
        if (tokens.isEmpty()) return;
        String normQuery = normalizeText(keyword);
        for (SongDTO song : merged) {
            double bonus = computeRelevanceBonus(song, tokens, normQuery);
            if (bonus > 0) {
                double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
                song.setFinalScore(base + bonus);
            }
        }
    }

    private double computeRelevanceBonus(SongDTO song, List<String> normTokens, String normQuery) {
        String normName = normalizeText(song.getName());
        if (normName.isEmpty()) return 0;
        String normArtist = normalizeText(song.getArtist());
        double bonus = 0;
        boolean nameMatch = false;
        if (!normQuery.isEmpty() && normName.equals(normQuery)) {
            bonus += RELEVANCE_EXACT_NAME_BONUS;
            nameMatch = true;
        } else {
            for (String t : normTokens) {
                if (normName.equals(t)) {
                    bonus += RELEVANCE_EXACT_NAME_BONUS;
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch) {
                for (String t : normTokens) {
                    if (normName.contains(t) || t.contains(normName)) {
                        bonus += RELEVANCE_NAME_CONTAINS_BONUS;
                        nameMatch = true;
                        break;
                    }
                }
            }
        }
        boolean artistMatch = false;
        if (!normArtist.isEmpty()) {
            for (String t : normTokens) {
                if (normArtist.contains(t) || t.contains(normArtist)) {
                    bonus += RELEVANCE_ARTIST_BONUS;
                    artistMatch = true;
                    break;
                }
            }
        }
        if (nameMatch && artistMatch) bonus += RELEVANCE_NAME_AND_ARTIST_BONUS;
        return bonus;
    }

    /**
     * 非 VIP 加权（merge 后统一追加，不碰 pickBest 试听版逻辑）。
     * vip == false（上游明确非付费）→ +0.5；vip == true → +0；
     * vip == null（上游未知，ES/DB 回填结果亦无此字段）→ +0：未知不得排到已知可用之前。
     */
    private void applyNonVipBonus(List<SongDTO> merged) {
        for (SongDTO song : merged) {
            if (Boolean.FALSE.equals(song.getVip())) {
                double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
                song.setFinalScore(base + NON_VIP_BONUS);
            }
        }
    }

    // ==================== QQ 熔断器状态机 ====================

    /**
     * 熔断开路期间是否应跳过 QQ 分支。半开时仅放行一个探针，其余并发请求继续跳过。
     * 开路时刻优先读 Redis（重启后新实例同样可见）；Redis 不可用或无状态时降级为进程内内存。
     */
    boolean shouldSkipQq() {
        long openedAt = readQqOpenedAt();
        if (openedAt < 0) return false;
        long now = qqClock.getAsLong();
        if (now - openedAt < QQ_BREAKER_OPEN_MS) return true;
        synchronized (qqBreakerLock) {
            openedAt = readQqOpenedAt();
            if (openedAt < 0) return false;
            now = qqClock.getAsLong();
            if (now - openedAt < QQ_BREAKER_OPEN_MS) return true;
            if (qqHalfOpenProbeInFlight) return true;
            qqHalfOpenProbeInFlight = true;
            return false;
        }
    }

    /** QQ 成功：清零计数、闭路（探针成功同样闭路）。内存与 Redis 双写，Redis 异常只降级不抛。 */
    void recordQqSuccess() {
        qqConsecutiveFailures.set(0);
        synchronized (qqBreakerLock) {
            qqCircuitOpenedAt = -1L;
            qqHalfOpenProbeInFlight = false;
        }
        try {
            if (stringRedisTemplate != null) stringRedisTemplate.delete(QQ_BREAKER_REDIS_KEY);
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 清除开路状态失败，降级为内存状态: {}", e.getMessage());
        }
    }

    /** QQ 失败/超时：累计；达阈值开路，探针失败则重开并重置计时。计数经 Redis 原子累加实现跨实例共享。 */
    void recordQqFailure() {
        int n = incrementQqFailures();
        synchronized (qqBreakerLock) {
            qqHalfOpenProbeInFlight = false;
            if (n >= QQ_BREAKER_FAILURE_THRESHOLD) {
                long now = qqClock.getAsLong();
                qqCircuitOpenedAt = now;
                writeQqOpenState(n, now);
            }
        }
    }

    /** 开路时刻：Redis 优先（-1 亦视为有效闭路状态），缺失/异常时用内存值。 */
    private long readQqOpenedAt() {
        try {
            HashOperations<String, String, String> ops = breakerHashOps();
            if (ops != null) {
                String v = ops.get(QQ_BREAKER_REDIS_KEY, QQ_BREAKER_F_OPENED_AT);
                if (v != null) return Long.parseLong(v);
            }
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 读取开路状态失败，降级为内存状态: {}", e.getMessage());
        }
        return qqCircuitOpenedAt;
    }

    /** 失败计数：Redis HINCRBY 原子跨实例累加并同步内存镜像；Redis 不可用时内存自增。 */
    private int incrementQqFailures() {
        try {
            HashOperations<String, String, String> ops = breakerHashOps();
            if (ops != null) {
                Long n = ops.increment(QQ_BREAKER_REDIS_KEY, QQ_BREAKER_F_FAILURES, 1L);
                if (n != null) {
                    int v = n.intValue();
                    qqConsecutiveFailures.set(v);
                    refreshQqBreakerTtl();
                    return v;
                }
            }
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 累计失败次数失败，降级为内存计数: {}", e.getMessage());
        }
        return qqConsecutiveFailures.incrementAndGet();
    }

    /** 开路写 Redis：failures + openedAt 双字段，TTL 恒等于开路窗口。异常只降级不抛。 */
    private void writeQqOpenState(int failures, long openedAt) {
        try {
            HashOperations<String, String, String> ops = breakerHashOps();
            if (ops == null) return;
            ops.put(QQ_BREAKER_REDIS_KEY, QQ_BREAKER_F_FAILURES, String.valueOf(failures));
            ops.put(QQ_BREAKER_REDIS_KEY, QQ_BREAKER_F_OPENED_AT, String.valueOf(openedAt));
            refreshQqBreakerTtl();
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 写入开路状态失败，降级为内存状态: {}", e.getMessage());
        }
    }

    /** 刷新熔断器键 TTL = 开路窗口；键过期即视为闭路。异常只降级不抛。 */
    private void refreshQqBreakerTtl() {
        try {
            if (stringRedisTemplate != null)
                stringRedisTemplate.expire(QQ_BREAKER_REDIS_KEY, QQ_BREAKER_OPEN_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 刷新熔断器 TTL 失败，降级为内存状态: {}", e.getMessage());
        }
    }

    private HashOperations<String, String, String> breakerHashOps() {
        try {
            if (stringRedisTemplate == null) return null;
            return stringRedisTemplate.opsForHash();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单次搜索内恰好计数一次：超时分支与任务收尾分支竞争，胜者计数，败者跳过。 */
    private void recordQqOutcomeOnce(AtomicBoolean claimed, boolean success) {
        if (claimed.compareAndSet(false, true)) {
            if (success) recordQqSuccess();
            else recordQqFailure();
        }
    }

    /** 全失败判定：实际发起的平台全部异常/超时（熔断跳过的 QQ 不计入，被跳过时只看网易云+咪咕）。 */
    private boolean allUpstreamFailed(boolean qqSkipped, AtomicBoolean neFailed,
                                      AtomicBoolean qqFailed, AtomicBoolean mgFailed) {
        if (!neFailed.get() || !mgFailed.get()) return false;
        return qqSkipped || qqFailed.get();
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchNetease(String keyword) {
        return safeSearchNetease(keyword, null);
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchNetease(String keyword, AtomicBoolean failed) {
        try {
            Map<String, Object> result = neteaseApiService.searchNetease(keyword, PER_PLATFORM_FETCH);
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return Collections.emptyList();
            return data.stream()
                    .map(this::parsePlatformSong).filter(Objects::nonNull)
                    .peek(this::rewriteHttpCoverUrl)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            log.error("Netease search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> fetchQQ(String keyword) {
        Map<String, Object> result = neteaseApiService.searchQQ(keyword, PER_PLATFORM_FETCH);
        if (result == null) return Collections.emptyList();
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        if (data == null) return Collections.emptyList();
        return data.stream()
                .map(this::parsePlatformSong).filter(Objects::nonNull)
                .peek(this::rewriteHttpCoverUrl)
                .collect(Collectors.toList());
    }

    /** 同步单平台 QQ 搜索：成功清零、异常计数（单次调用恰好计数一次）。 */
    private List<SongDTO> safeSearchQQ(String keyword) {
        try {
            List<SongDTO> songs = fetchQQ(keyword);
            recordQqSuccess();
            return songs;
        } catch (Exception e) {
            recordQqFailure();
            log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchMigu(String keyword) {
        return safeSearchMigu(keyword, null);
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchMigu(String keyword, AtomicBoolean failed) {
        try {
            Map<String, Object> result = neteaseApiService.searchMigu(keyword, PER_PLATFORM_FETCH);
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return Collections.emptyList();
            return data.stream()
                    .map(this::parsePlatformSong).filter(Objects::nonNull)
                    .peek(this::rewriteHttpCoverUrl)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            log.error("Migu search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private void rewriteHttpCoverUrl(SongDTO song) {
        if (song.getCoverUrl() != null && song.getCoverUrl().startsWith("http://")) {
            String encoded = java.net.URLEncoder.encode(song.getCoverUrl(), java.nio.charset.StandardCharsets.UTF_8);
            song.setCoverUrl("/api/image-proxy?url=" + encoded);
        }
    }

    private SongDTO parsePlatformSong(Map<String, Object> raw) {
        try {
            String sourceId = raw.get("id") != null ? String.valueOf(raw.get("id")) : "";
            if (sourceId.isEmpty()) return null;
            String name = raw.get("name") != null ? String.valueOf(raw.get("name")) : "";
            String artists = raw.get("artists") != null ? String.valueOf(raw.get("artists")) : "未知歌手";
            String album = raw.get("album") != null ? String.valueOf(raw.get("album")) : "";
            String coverUrl = raw.get("cover") != null ? String.valueOf(raw.get("cover")) : "";
            int duration = 0;
            Object durObj = raw.get("duration");
            if (durObj instanceof Number) duration = ((Number) durObj).intValue() / 1000;
            Object vipObj = raw.get("vip");
            // 上游未知保持 null（中性、不得分），只有明确非付费才加权，见 applyNonVipBonus
            Boolean vip = vipObj instanceof Boolean ? (Boolean) vipObj : null;

            SongDTO dto = new SongDTO();
            dto.setSourceId(sourceId); dto.setName(name); dto.setArtist(artists);
            dto.setAlbum(album); dto.setCoverUrl(coverUrl); dto.setDuration(duration);
            dto.setVip(vip);
            return dto;
        } catch (Exception e) {
            log.warn("Failed to parse platform song: {}", e.getMessage());
            return null;
        }
    }

    private <T> List<T> getWithTimeout(Future<List<T>> future, int seconds, String platform) {
        return getWithTimeout(future, seconds, platform, null);
    }

    private <T> List<T> getWithTimeout(Future<List<T>> future, int seconds, String platform, AtomicBoolean failed) {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            log.warn("{} search timed out after {}s", platform, seconds);
            future.cancel(true);
            if (failed != null) failed.set(true);
        }
        catch (Exception e) {
            log.error("{} search failed: {}", platform, e.getMessage());
            if (failed != null) failed.set(true);
        }
        return Collections.emptyList();
    }

    /** QQ 专属超时等待：超时/异常计失败（与任务内计数互斥，单次搜索恰好计数一次）。 */
    private List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed) {
        return getQqWithTimeout(future, claimed, null);
    }

    /** QQ 专属超时等待：超时/异常计失败（与任务内计数互斥，单次搜索恰好计数一次）。 */
    private List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed, AtomicBoolean failed) {
        try {
            return future.get(SEARCH_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("QQ search timed out after {}s", SEARCH_TIMEOUT_SEC);
            future.cancel(true);
            recordQqOutcomeOnce(claimed, false);
            if (failed != null) failed.set(true);
        } catch (Exception e) {
            log.error("QQ search failed: {}", e.getMessage());
            recordQqOutcomeOnce(claimed, false);
            if (failed != null) failed.set(true);
        }
        return Collections.emptyList();
    }
}
