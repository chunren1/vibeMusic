package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.common.resilience.UpstreamBreaker;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 歌曲搜索服务（facade）：缓存 → 单飞 → 平台扇出 → 合并排序 → 分页。
 *
 * <p>职责拆分（P1-8，2026-10-25）：
 * <ul>
 *   <li>{@link PlatformSearchAdapter}：五平台单次搜索、超时等待、失败上报</li>
 *   <li>{@link QqCircuitBreaker}：QQ 熔断状态机（进程内 + Redis 跨实例）</li>
 *   <li>{@link SongDtoParser}：网关结果解析与封面改写</li>
 *   <li>{@link SongMerger}：跨平台去重与加权排序</li>
 * </ul>
 *
 * <p>二级缓存：Redis → API 实时搜索；支持分源搜索（网易云 / QQ / 咪咕 / 酷狗 / B站）。
 * 线程池由 Spring 托管（ThreadPoolConfig），避免 static ExecutorService 泄漏。
 */
@Slf4j
@Service
public class SongSearchService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;
    private final MeterRegistry meterRegistry;
    private final ThreadPoolTaskExecutor searchExecutor;
    private final ThreadPoolTaskExecutor warmExecutor;

    /** QQ 熔断状态机（进程内状态 + Redis 跨实例持久化）。 */
    private final QqCircuitBreaker qqBreaker;

    /** 平台适配层：五平台单次搜索 / 超时 / 失败上报。 */
    private final PlatformSearchAdapter platformSearch;

    public SongSearchService(SongMapper songMapper,
                             NeteaseApiService neteaseApiService,
                             SongCacheService cacheService,
                             MeterRegistry meterRegistry,
                             ThreadPoolTaskExecutor searchExecutor,
                             ThreadPoolTaskExecutor warmExecutor,
                             StringRedisTemplate stringRedisTemplate) {
        this.songMapper = songMapper;
        this.neteaseApiService = neteaseApiService;
        this.cacheService = cacheService;
        this.meterRegistry = meterRegistry;
        this.searchExecutor = searchExecutor;
        this.warmExecutor = warmExecutor;
        this.qqBreaker = new QqCircuitBreaker(stringRedisTemplate);
        this.platformSearch = new PlatformSearchAdapter(
                neteaseApiService, upstreamBreaker, qqBreaker, this::recordSourceOutcome);
    }

    /**
     * 网关（musicapi）整体熔断器：连续传输失败开路，避免 5 平台同时空转。
     * 纯内存、进程内（单实例假设，与 QQ 熔断器一致），随 service 单例创建。
     */
    private final UpstreamBreaker upstreamBreaker = new UpstreamBreaker();

    /** 测试用：直接拿熔断器注入状态/假时钟。 */
    UpstreamBreaker upstreamBreaker() {
        return upstreamBreaker;
    }

    private UserService userService;

    @Autowired(required = false)
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** 当前用户的网易云 Cookie；解析逻辑收敛在 UserService（单一事实源）。 */
    private String resolveUserCookie() {
        return userService == null ? null : userService.resolveCurrentNeteaseCookie();
    }

    // 缓存命中率仪表盘
    private Counter redisHitCounter;
    private Counter apiCallCounter;
    private Counter searchRejectedCounter;
    private Counter upstreamAllFailedCounter;
    private Timer searchTimer;

    @PostConstruct
    void initMetrics() {
        redisHitCounter = Counter.builder("cache.hit.redis")
                .description("Redis 缓存命中次数").register(meterRegistry);
        apiCallCounter = Counter.builder("cache.miss.api")
                .description("穿透到 musicapi 的次数").register(meterRegistry);
        searchRejectedCounter = Counter.builder("search.pool.rejected")
                .description("搜索线程池过载拒绝次数").register(meterRegistry);
        upstreamAllFailedCounter = Counter.builder("search.upstream.all_failed")
                .description("五平台上游全部失败次数").register(meterRegistry);
        searchTimer = Timer.builder("search.latency")
                .description("搜索总耗时").register(meterRegistry);
        for (String source : WEIGHTED_SOURCES) {
            final String platform = source;
            Gauge.builder("search.source.weight", () -> effectiveWeight(platform, baseWeightFor(platform)))
                    .tag("platform", platform)
                    .description("搜索源动态权重（含连续失败降权）").register(meterRegistry);
        }
    }

    // ==================== 热门关键词预热 ====================

    private static final List<String> HOT_KEYWORDS = List.of(
            "周杰伦", "晴天", "陈奕迅", "告白气球",
            "稻香", "夜曲", "七里香", "热歌"
    );

    /**
     * 随机推荐关键词池：getRandomSongs() 每次调用从中随机挑一个词做单次上游搜索，
     * 避免固定搜"热歌"导致候选池恒为 top-30。预热时与 HOT_KEYWORDS 取并集。
     */
    private static final List<String> RANDOM_KEYWORDS = List.of(
            "热歌", "新歌", "流行", "华语", "民谣",
            "摇滚", "电子", "轻音乐", "经典", "影视原声"
    );

    /**
     * 热门搜索关键词（App 热搜云端同步与空结果纠错共用同一份表）。
     * 返回副本，调用方不可修改内部表。
     */
    public static List<String> hotKeywords() {
        return List.copyOf(HOT_KEYWORDS);
    }

    /**
     * 启动时异步预热热门搜索词到 Redis 缓存，
     * 避免上线后的首次 API 穿透造成 P95 延迟飙升。
     * 每个关键词间隔 1.5 秒防止同时击穿第三方 API。
     * <p>
     * 触发时机：{@link ApplicationReadyEvent}（容器就绪后）而非 {@code @PostConstruct}。
     * 旧实现挂在 context refresh 期间，17 个关键词 × 5 路上游在 2 vCPU 上与主线程抢 CPU，
     * 实测把生产冷启动拖长约 47 秒；改为就绪后延迟执行，让启动路径先跑完。
     */
    @Value("${app.prewarm.enabled:true}")
    boolean prewarmEnabled;

    /** 预热起始延迟（秒）：等启动路径与首批健康检查过去再打上游，默认 20s。 */
    @Value("${app.prewarm.initial-delay-seconds:20}")
    long prewarmDelaySeconds;

    @EventListener(ApplicationReadyEvent.class)
    void preWarmHotKeywords() {
        if (!prewarmEnabled) {
            log.info("[PREWARM] 预热已关闭（app.prewarm.enabled=false），跳过热门关键词预热");
            return;
        }
        warmExecutor.submit(() -> {
            try {
                Thread.sleep(prewarmDelaySeconds * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("[PREWARM] 预热等待被中断，放弃预热");
                return;
            }
            // 预热 HOT_KEYWORDS 与 RANDOM_KEYWORDS 的并集（去重），随机池命中时同样走缓存。
            Set<String> warmKeywords = new LinkedHashSet<>(HOT_KEYWORDS);
            warmKeywords.addAll(RANDOM_KEYWORDS);
            log.info("[PREWARM] 开始预热 {} 个热门搜索关键词 ...", warmKeywords.size());
            for (String kw : warmKeywords) {
                try {
                    search(kw, 1, 20);
                    log.info("[PREWARM] '{}' 预热完成 (缓存已回写 Redis)", kw);
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[PREWARM] '{}' 预热失败: {}", kw, e.getMessage(), e);
                }
            }
            log.info("[PREWARM] 热门搜索关键词预热全部完成");
        });
    }

    private static final double NET_WEIGHT = 1.5;
    private static final double MIGU_WEIGHT = 1.4;
    // 酷狗权重 1.0：介于咪咕(1.4)与 QQ(0.8)之间。phase 1 匿名仅 128k/320k，
    // 覆盖与元数据完整度不及咪咕签名的 PQ 链路，但优于无 Cookie 的 QQ(且不受熔断器降权)，
    // 故取中点，保持 netease > migu > kugou > qq 排序。
    private static final double KUGOU_WEIGHT = 1.0;
    // B 站权重 0.7：guest 132–192k AAC 实测可播，翻唱/古风/Live/OST 覆盖独特；
    // 但视频标题元数据噪音大(网关已做标题解析仍残留歧义回退)，故置于 QQ(0.8)之下、
    // 保持 netease > migu > kugou > qq > bilibili 排序（2026-09-30 实测 QQ 链路已健康，
    // QQ 回正版音源曲库行，排位回到酷狗之下、B站之上）。
    // (2026-09 质量 pass 由 0.8 下调：质量区分改由播放量门控承担，权重只定基准档；
    // 降 0.1 使无相关性加成时的 B 站首名(0.7)不再天然压过 QQ 首名太多，
    // 强 B 站行仍可靠精确歌名 +2.0 加成上浮，弱行则由门控封顶沉底。)
    private static final double BILI_WEIGHT = 0.7;
    // QQ 权重 0.8（2026-09-30 由 0.6 上调：QQ 链路实测已健康，不再是"双死"备源；
    // 0.6 会把 QQ 独家原唱在结构上埋掉——同名精确匹配下永远追不上咪咕/网易头名。
    // 仍在酷狗 1.0 之下、B站 0.7 之上，只收窄差距。
    private static final double QQ_WEIGHT = 0.8;
    // B 站播放量门控(2026-09 质量 pass)：网关 search/type 实测(热词'晴天' top10)：
    // 成熟音乐投稿播放量 10万–1700万、低质搬运/广告混剪 <5万(如 vivo 广告 2.5万)，
    // 弹幕 36–8万；MV 类投稿常低弹幕(如 98万播放/95弹幕)，故双信号取 OR。
    // 强行 = 有封面 + 标题成形 + (播放>=10万 OR 弹幕>=1000)；弱行(纯 B 站来源)封顶沉底。
    // 弱 B 站行封顶 0.5：严格低于 QQ 首名基准分(0.8/1=0.8)，保证弱行永远排不过
    // 任何平台的头名；相关性加成照常计算后再封顶(标题再贴切也不得越线）。
    // 同曲多源合并加成：同一首歌每多一个可播平台 +0.5（多源≈正版/热门版本，
    // 这是网易/QQ/咪咕/酷狗不带播放量字段下唯一可用的热度代理），总额封顶 +1.0。
    // 网易头名加成 +0.5：网易排序≈C-pop 热度排序，头名即热度第一的强信号。
    // 查询相关性加分叠加在平台分之上，不改变平台权重与跨平台加分。
    private static final int PER_PLATFORM_FETCH = 40;
    /**
     * 搜索 fanout 总预算（外层）。必须严格大于内层 HTTP 响应超时
     * {@link com.vibemusic.config.RestTemplateConfig#SEARCH_RESPONSE_TIMEOUT}（3s），
     * 留 1s 给线程池调度与结果合并；包内可见以便超时预算自洽测试断言。
     */
    static final int SEARCH_TIMEOUT_SEC = PlatformSearchAdapter.SEARCH_TIMEOUT_SEC;
    // QQ 专属预算 2s（2026-09-29 实测：QQ 仅 25% 搜索有贡献，权重又最低；
    // 4s 预算下它是常见的尾部拖慢者，减半只截长尾，健康时 2s 内必回）
    static final int QQ_TIMEOUT_SEC = PlatformSearchAdapter.QQ_TIMEOUT_SEC;
    /** 搜索 keyword 最大长度：超长截断，防上游/缓存键膨胀（与 size<=100 同属入参校验层）。 */
    public static final int MAX_KEYWORD_LENGTH = 200;
    /** 随机推荐 count 上限：直通 DB LIMIT（含 lyric TEXT），必须封顶。 */
    public static final int MAX_RANDOM_COUNT = 50;
    // 非 VIP 加权：用户侧无 VIP，适度降权付费内容（见 applyNonVipBonus 注释）。
    /**
     * 标题长度罚分：超过阈值的标题按超出字数线性扣分并封顶。
     * 阈值 30 字：正常单曲中文名多为 1–10 字，即使带 "(Live版)" / "feat. X" 等
     * 后缀也极少超 30；而拼盘/串烧/合集类标题常靠堆砌歌名冲到 50 字以上。
     * 取 B 站强行判定(>60 字判弱)的一半，保守起见只打超长、不误伤正常歌名。
     * 步长 0.05/字：超出 20 字扣 1.0，恰好抵消一档歌名包含加成(+1.0)，渐进而非一刀切。
     * 封顶 2.0 = 精确歌名加成(+2.0)：标题堆砌至多 cancel 掉精确匹配红利，
     * 不超过最大相关性信号；扣分后下限钳制 0，保证排序分非负、返回结构不变。
     * 按码点计数：emoji 堆砌标题按一个可见字符计，不因 UTF-16 代理对被双倍惩罚。
     */
    // 未获单飞锁时的 bounded 退避轮询：持锁者仍在搜索中，立即重读必 miss；
    // 短暂等待让持锁者回写缓存，并发同关键词请求共享结果而非各自穿透上游。
    // 退避序列 150/300/600/1200ms（累计 2.25s），仍 < SEARCH_TIMEOUT_SEC=4s 预算，
    // 既能真正等到持锁者回写，也不会拖慢"持锁者已挂"时的兜底直查。
    private static final long[] LOCK_WAIT_BACKOFF_MS = {150L, 300L, 600L, 1200L};
    /**
     * 退避轮询总时长（150+300+600+1200=2250ms）。
     * Redis 不可用时这段等待必然落空（每轮重读都失败），直接跳过；日志/文档据此说明省下的耗时。
     */
    static final long LOCK_BACKOFF_TOTAL_MS = sumOf(LOCK_WAIT_BACKOFF_MS);

    private static long sumOf(long[] values) {
        long total = 0L;
        for (long v : values) total += v;
        return total;
    }
    // QQ 熔断器阈值：连续失败/超时达此次数开路；开路时长 5 分钟（状态机见 QqCircuitBreaker）。
    static final int QQ_BREAKER_FAILURE_THRESHOLD = QqCircuitBreaker.FAILURE_THRESHOLD;
    static final long QQ_BREAKER_OPEN_MS = QqCircuitBreaker.OPEN_MS;
    // 动态源权重：各平台连续失败（异常/超时；空结果属健康应答，不计）自动降权，
    // 成功一次即复权。档位 1 → 1/2 → 1/4 → 1/8 封顶，健康时因子恒为 1（排序与旧权重逐分一致）。
    static final int SOURCE_DEWEIGHT_MAX_STEPS = 3;
    private static final List<String> WEIGHTED_SOURCES = List.of("netease", "migu", "kugou", "bilibili", "qq");
    private final ConcurrentHashMap<String, AtomicInteger> sourceFailures = new ConcurrentHashMap<>();

    static double deweightFactor(int consecutiveFailures) {
        int steps = Math.max(0, Math.min(consecutiveFailures, SOURCE_DEWEIGHT_MAX_STEPS));
        return Math.pow(0.5, steps);
    }

    static double baseWeightFor(String platform) {
        if ("netease".equals(platform)) return NET_WEIGHT;
        if ("migu".equals(platform)) return MIGU_WEIGHT;
        if ("kugou".equals(platform)) return KUGOU_WEIGHT;
        if ("bilibili".equals(platform)) return BILI_WEIGHT;
        if ("qq".equals(platform)) return QQ_WEIGHT;
        return 1.0;
    }

    double effectiveWeight(String platform, double base) {
        AtomicInteger c = sourceFailures.get(platform);
        return base * deweightFactor(c == null ? 0 : c.get());
    }

    void recordSourceOutcome(String platform, boolean success) {
        if (platform == null) return;
        if (success) {
            AtomicInteger c = sourceFailures.get(platform);
            if (c != null) c.set(0);
        } else {
            sourceFailures.computeIfAbsent(platform, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    int getSourceFailures(String platform) {
        AtomicInteger c = sourceFailures.get(platform);
        return c == null ? 0 : c.get();
    }

    /** QQ 熔断器 Redis 键（状态机内部实现，此处保留常量供测试/运维对齐）。 */
    static final String QQ_BREAKER_REDIS_KEY = QqCircuitBreaker.REDIS_KEY;

    /** 测试用时钟注入（生产恒为 System::currentTimeMillis）。 */
    void setQqClockForTest(LongSupplier clock) {
        qqBreaker.setClockForTest(clock);
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
        return search(keyword, page, size, platform, resolveUserCookie());
    }

    @SuppressWarnings("unchecked")
    public SearchResult search(String keyword, int page, int size, String platform, String userCookie) {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));
        if (keyword == null || keyword.trim().isEmpty())
            return SearchResult.of(Collections.emptyList(), 0, page, size, "none");
        if (userCookie != null && userCookie.isBlank()) userCookie = null;
        final String userCk = userCookie;
        final boolean perUser = userCk != null;
        String rawKw = SearchQueryHints.normalizeQuery(keyword);
        if (rawKw.isEmpty())
            return SearchResult.of(Collections.emptyList(), 0, page, size, "none");
        if (rawKw.length() > MAX_KEYWORD_LENGTH) rawKw = rawKw.substring(0, MAX_KEYWORD_LENGTH);
        final String kw = rawKw;
        final boolean searchBoth = (platform == null || platform.trim().isEmpty());
        final long searchStart = System.currentTimeMillis();

        String cacheExtra = searchBoth ? "all" : platform.trim().toLowerCase();

        // ======== 第 1 步：Redis 缓存（null=未命中；空 List=负缓存哨兵命中，直接返回空） ========
        long redisStart = System.currentTimeMillis();
        // per-user 请求绕过全部共享缓存读写（Redis/ES/单飞锁）：VIP 结果绝不落入
        // 共享键，也绝不读取他人/匿名缓存；直查上游，结果仅当次返回
        List<SongDTO> cached = null;
        if (!perUser) {
            cached = cacheService.getSearchCache(kw + ":" + cacheExtra);
        }
        if (cached != null) {
            redisHitCounter.increment();
            searchTimer.record(System.currentTimeMillis() - searchStart, TimeUnit.MILLISECONDS);
            long redisCost = System.currentTimeMillis() - redisStart;
            log.info("[CACHE-LAYER] Redis 命中: keyword='{}', page={}, totalCost={}ms",
                    kw, page, redisCost);
            int from = (page - 1) * size;
            int to = Math.min(from + size, cached.size());
            if (from >= cached.size()) return SearchResult.of(Collections.emptyList(), cached.size(), page, size, "redis",
                    cached.isEmpty() ? SearchQueryHints.suggestForEmpty(kw, HOT_KEYWORDS, false) : null);
            return SearchResult.of(cached.subList(from, to), cached.size(), page, size, "redis");
        }
        long redisCost = System.currentTimeMillis() - redisStart;
        log.info("[CACHE-LAYER] Redis 未命中: keyword='{}', page={}, cost={}ms", kw, page, redisCost);

        // ======== 第 2 步：API 实时搜索（兜底）======
        // 分布式锁单飞：持锁者执行搜索并回写缓存；未获锁者短暂等待后重试读缓存，
        // 仍无缓存则兜底直接执行（锁持有者异常/过慢时不阻塞请求）
        String allCacheKey = kw + ":" + cacheExtra;
        ApiSearchOutcome outcome;
        if (perUser) {
            outcome = doApiSearch(kw, cacheExtra, allCacheKey, userCk, searchStart);
        } else {
            SongCacheService.LockResult lock = cacheService.tryLockResult(allCacheKey);
            if (lock.status() == SongCacheService.LockResult.Status.UNAVAILABLE) {
                // Redis 不可用：退避轮询每轮都要重读缓存，而 Redis 挂了重读必然失败，
                // 白等 2.25s（150+300+600+1200）后仍要直查 → 直接跳过单飞等待。
                log.warn("[API-LAYER] Redis 不可用，跳过单飞等待直查: keyword='{}', 省去退避 {}ms",
                        kw, LOCK_BACKOFF_TOTAL_MS);
                outcome = doApiSearch(kw, cacheExtra, allCacheKey, null, searchStart);
            } else if (lock.status() == SongCacheService.LockResult.Status.BUSY) {
                List<SongDTO> retried = null;
                for (int round = 0; round < LOCK_WAIT_BACKOFF_MS.length; round++) {
                    // 指数退避：持锁者通常 1-2s 内回写，早轮重读注定 miss 且空耗 CPU/Redis
                    long backoff = LOCK_WAIT_BACKOFF_MS[round];
                    if (System.currentTimeMillis() - searchStart + backoff > SEARCH_TIMEOUT_SEC * 1000L) {
                        log.info("[API-LAYER] 单飞退避超出搜索预算，提前兜底直查: keyword='{}'", kw);
                        break;
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    retried = cacheService.getSearchCache(allCacheKey);
                    if (retried != null) {
                        log.info("[CACHE-LAYER] 等待后命中单飞结果: keyword='{}', round={}, waited={}ms", kw, round + 1, backoff);
                        int from = (page - 1) * size;
                        int to = Math.min(from + size, retried.size());
                        if (from >= retried.size()) return SearchResult.of(Collections.emptyList(), retried.size(), page, size, "redis",
                                retried.isEmpty() ? SearchQueryHints.suggestForEmpty(kw, HOT_KEYWORDS, false) : null);
                        return SearchResult.of(retried.subList(from, to), retried.size(), page, size, "redis");
                    }
                }
                log.info("[API-LAYER] 单飞等待超时仍无缓存，兜底直查: keyword='{}', 原因=持锁者未及时回写", kw);
                outcome = doApiSearch(kw, cacheExtra, allCacheKey, null, searchStart);
            } else {
                try {
                    outcome = doApiSearch(kw, cacheExtra, allCacheKey, null, searchStart);
                } finally {
                    cacheService.releaseLock(allCacheKey, lock.lockValue());
                }
            }
        }
        List<SongDTO> resultList = outcome.songs();

        // 处理分页返回（空结果带回退建议：别名/纠错；全上游失败时无建议、仅重试）
        String suggestion = resultList.isEmpty()
                ? SearchQueryHints.suggestForEmpty(kw, HOT_KEYWORDS, outcome.allUpstreamFailed())
                : null;
        int from = (page - 1) * size;
        int to = Math.min(from + size, resultList.size());
        if (from >= resultList.size())
            return SearchResult.of(Collections.emptyList(), resultList.size(), page, size, "api", suggestion);
        searchTimer.record(System.currentTimeMillis() - searchStart, TimeUnit.MILLISECONDS);
        return SearchResult.of(resultList.subList(from, to), resultList.size(), page, size, "api");
    }

    /**
     * 执行第三方 API 搜索（单平台或双平台合并去重），并回写 Redis 缓存。
     * 调用方负责单飞锁的获取与释放。
     */
    /** 上游搜索结果 + 是否全上游失败（决定空结果写哨兵与否、以及是否给改写建议）。 */
    private record ApiSearchOutcome(List<SongDTO> songs, boolean allUpstreamFailed) {}

    private ApiSearchOutcome doApiSearch(String kw, String cacheExtra, String allCacheKey, String userCookie,
                                        long searchStart) {
        apiCallCounter.increment();
        log.info("[API-LAYER] 触发实时搜索(单飞): keyword='{}', 原因=Redis未命中", kw);
        long apiStart = System.currentTimeMillis();
        final boolean perUser = userCookie != null;

        // 网关整体熔断：开路时跳过全部 5 个远程分支，只走「空结果 + 重试提示」的本地兜底，
        // 避免 5 线程各自等连接拒绝/超时（单平台熔断管不了网关整个不可用）。
        final boolean upstreamAllowed = upstreamBreaker.allow();
        if (!upstreamAllowed) {
            log.warn("[UPSTREAM-BREAKER] 网关整体熔断开路，跳过 5 平台远程检索: keyword='{}', remainingMs={}, 返回空结果不写负缓存",
                    kw, upstreamBreaker.remainingOpenMs());
        }

        if ("netease".equals(cacheExtra)) {
            if (!upstreamAllowed) return upstreamUnavailableOutcome();
            AtomicBoolean neFailedOnce = new AtomicBoolean(false);
            List<SongDTO> songs = new ArrayList<>(platformSearch.safeSearchNetease(kw, neFailedOnce, userCookie));
            recordSourceOutcome("netease", !neFailedOnce.get());
            for (SongDTO s : songs) s.setPlatform("netease");
            if (!songs.isEmpty() && !perUser) cacheService.setSearchCache(kw + ":netease", songs, true);
            return new ApiSearchOutcome(songs, false);
        }
        if ("qq".equals(cacheExtra)) {
            if (!upstreamAllowed) return upstreamUnavailableOutcome();
            if (shouldSkipQq()) {
                // 半开探针被 QQ 平台熔断跳过：本次没有远程调用，必须释放探针占位（不计数），
                // 否则网关熔断器会永远停在半开。
                upstreamBreaker.recordAttemptFailure(false);
                log.warn("[QQ-BREAKER] 熔断中跳过QQ: keyword='{}'", kw);
                return new ApiSearchOutcome(Collections.emptyList(), false);
            }
            List<SongDTO> songs = new ArrayList<>(platformSearch.safeSearchQQ(kw));
            for (SongDTO s : songs) s.setPlatform("qq");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":qq", songs, true);
            return new ApiSearchOutcome(songs, false);
        }
        if ("migu".equals(cacheExtra)) {
            if (!upstreamAllowed) return upstreamUnavailableOutcome();
            AtomicBoolean mgFailedOnce = new AtomicBoolean(false);
            List<SongDTO> songs = new ArrayList<>(platformSearch.safeSearchMigu(kw, mgFailedOnce));
            recordSourceOutcome("migu", !mgFailedOnce.get());
            for (SongDTO s : songs) s.setPlatform("migu");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":migu", songs, true);
            return new ApiSearchOutcome(songs, false);
        }
        if ("kugou".equals(cacheExtra)) {
            if (!upstreamAllowed) return upstreamUnavailableOutcome();
            AtomicBoolean kgFailedOnce = new AtomicBoolean(false);
            List<SongDTO> songs = new ArrayList<>(platformSearch.safeSearchKugou(kw, kgFailedOnce));
            recordSourceOutcome("kugou", !kgFailedOnce.get());
            for (SongDTO s : songs) s.setPlatform("kugou");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":kugou", songs, true);
            return new ApiSearchOutcome(songs, false);
        }
        if ("bilibili".equals(cacheExtra)) {
            if (!upstreamAllowed) return upstreamUnavailableOutcome();
            AtomicBoolean biFailedOnce = new AtomicBoolean(false);
            List<SongDTO> songs = new ArrayList<>(platformSearch.safeSearchBili(kw, biFailedOnce));
            recordSourceOutcome("bilibili", !biFailedOnce.get());
            for (SongDTO s : songs) s.setPlatform("bilibili");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":bilibili", songs, true);
            return new ApiSearchOutcome(songs, false);
        }

        // 合并搜索（复用 Spring 托管线程池）；熔断开路时跳过 QQ 分支，不再吃 4s 超时。
        // 线程池过载 submit 同步抛 RejectedExecutionException → 快速失败转可重试错误，不静默丢任务。
        // 网关整体熔断开路时连 5 个 submit 都不发：空转的连接拒绝/超时会白占线程与 DB 连接窗口。
        if (!upstreamAllowed) return upstreamUnavailableOutcome();
        final AtomicBoolean neFailed = new AtomicBoolean(false);
        final AtomicBoolean qqFailed = new AtomicBoolean(false);
        final AtomicBoolean mgFailed = new AtomicBoolean(false);
        final AtomicBoolean kgFailed = new AtomicBoolean(false);
        final AtomicBoolean biFailed = new AtomicBoolean(false);
        final Future<List<SongDTO>> neF;
        final Future<List<SongDTO>> qqF;
        final Future<List<SongDTO>> mgF;
        final Future<List<SongDTO>> kgF;
        final Future<List<SongDTO>> biF;
        final AtomicBoolean qqOutcomeClaimed = new AtomicBoolean(false);
        try {
            neF = searchExecutor.submit(() -> platformSearch.safeSearchNetease(kw, neFailed, userCookie));
            Future<List<SongDTO>> qqFuture = null;
            if (shouldSkipQq()) {
                log.warn("[QQ-BREAKER] 熔断中跳过QQ: keyword='{}'", kw);
            } else {
                qqFuture = searchExecutor.submit(() -> {
                    try {
                        List<SongDTO> songs = platformSearch.fetchQQ(kw);
                        upstreamBreaker.recordSuccess();
                        recordQqOutcomeOnce(qqOutcomeClaimed, true);
                        return songs;
                    } catch (Exception e) {
                        platformSearch.recordUpstreamFailure(e);
                        recordQqOutcomeOnce(qqOutcomeClaimed, false);
                        qqFailed.set(true);
                        log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
                        return Collections.emptyList();
                    }
                });
            }
            qqF = qqFuture;
            mgF = searchExecutor.submit(() -> platformSearch.safeSearchMigu(kw, mgFailed));
            kgF = searchExecutor.submit(() -> platformSearch.safeSearchKugou(kw, kgFailed));
            biF = searchExecutor.submit(() -> platformSearch.safeSearchBili(kw, biFailed));
        } catch (RejectedExecutionException rex) {
            // ThreadPoolTaskExecutor.submit 把拒绝包装为 TaskRejectedException（仍是 RejectedExecutionException 子类），此处一并捕获。
            searchRejectedCounter.increment();
            // 线程池拒绝属于本地过载，不是传输失败：只释放半开探针占位，不计数
            upstreamBreaker.recordAttemptFailure(false);
            log.warn("[SEARCH-POOL] 搜索线程池过载，快速失败: keyword='{}', error={}", kw, rex.getMessage());
            throw new BusinessException(503, "搜索服务繁忙，请稍后重试");
        }
        // 全局 fanout deadline：5 路并行提交后串行 get，若每路各等 4s，最坏叠加 4+2+4+4+4=18s
        //（17s 毛刺根因）。此处以 SEARCH_TIMEOUT_SEC 为总预算，后到的源只等剩余时间。
        long fanoutDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_SEC * 1000L;
        List<SongDTO> neteaseSongs = platformSearch.getWithTimeout(neF, remainingSec(fanoutDeadlineMs, SEARCH_TIMEOUT_SEC), "Netease", neFailed);
        List<SongDTO> qqSongs = (qqF == null) ? Collections.emptyList() : platformSearch.getQqWithTimeout(qqF, qqOutcomeClaimed, qqFailed, remainingSec(fanoutDeadlineMs, QQ_TIMEOUT_SEC));
        List<SongDTO> miguSongs = platformSearch.getWithTimeout(mgF, remainingSec(fanoutDeadlineMs, SEARCH_TIMEOUT_SEC), "Migu", mgFailed);
        List<SongDTO> kugouSongs = platformSearch.getWithTimeout(kgF, remainingSec(fanoutDeadlineMs, SEARCH_TIMEOUT_SEC), "Kugou", kgFailed);
        List<SongDTO> biliSongs = platformSearch.getWithTimeout(biF, remainingSec(fanoutDeadlineMs, SEARCH_TIMEOUT_SEC), "Bili", biFailed);
        recordSourceOutcome("netease", !neFailed.get());
        if (qqF != null) recordSourceOutcome("qq", !qqFailed.get());
        recordSourceOutcome("migu", !mgFailed.get());
        recordSourceOutcome("kugou", !kgFailed.get());
        recordSourceOutcome("bilibili", !biFailed.get());
        List<SongDTO> merged = mergePlatformResults(neteaseSongs, qqSongs, miguSongs, kugouSongs, biliSongs, kw);

        boolean incomplete = neteaseSongs.isEmpty() || qqSongs.isEmpty() || miguSongs.isEmpty() || kugouSongs.isEmpty() || biliSongs.isEmpty();
        log.info("[API-LAYER] 搜索完成: keyword='{}', 网易云={}首, QQ={}首, 咪咕={}首, 酷狗={}首, B站={}首, 去重后={}首, API-cost={}ms, totalCost={}ms",
                kw, neteaseSongs.size(), qqSongs.size(), miguSongs.size(), kugouSongs.size(), biliSongs.size(), merged.size(),
                System.currentTimeMillis() - apiStart, System.currentTimeMillis() - searchStart);
        if (merged.isEmpty() && PlatformSearchAdapter.allUpstreamFailed(qqF == null, neFailed, qqFailed, mgFailed, kgFailed, biFailed)) {
            upstreamAllFailedCounter.increment();
            log.warn("[API-LAYER] 五平台上游全部失败，不写空哨兵: keyword='{}'", kw);
            return new ApiSearchOutcome(merged, true);
        }
        if (!perUser) {
            cacheService.setSearchCache(allCacheKey, merged, !merged.isEmpty(), incomplete);
        }
        return new ApiSearchOutcome(merged, false);
    }

    /**
     * 网关整体熔断开路时的结果：不发任何远程请求，返回「全上游失败」空结果。
     *
     * <p>{@link #search} 据 allUpstreamFailed=true 不给改写建议（客户端只提示重试），
     * 且 doApiSearch 不会写空哨兵污染缓存 —— 与「五平台全失败」的收尾行为一致，
     * 只是省掉了 5 线程的连接拒绝/超时空转。
     *
     * <p>说明：当前搜索路径没有 DB 兜底分支（songMapper 仅用于 getRandomSongs），
     * 因此开路时不会有本地结果被丢弃；后续若新增 DB/本地兜底，必须放在本分支之前执行。
     */
    private ApiSearchOutcome upstreamUnavailableOutcome() {
        return new ApiSearchOutcome(Collections.emptyList(), true);
    }

    /** 合并五平台结果：委托给无状态的 SongMerger（权重按熔断降权后的实时值传入）。 */
    private List<SongDTO> mergePlatformResults(List<SongDTO> neteaseSongs, List<SongDTO> qqSongs,
                                               List<SongDTO> miguSongs, List<SongDTO> kugouSongs,
                                               List<SongDTO> biliSongs, String keyword) {
        return SongMerger.merge(neteaseSongs, qqSongs, miguSongs, kugouSongs, biliSongs, keyword,
                Map.of(
                        "netease", effectiveWeight("netease", NET_WEIGHT),
                        "qq", effectiveWeight("qq", QQ_WEIGHT),
                        "migu", effectiveWeight("migu", MIGU_WEIGHT),
                        "kugou", effectiveWeight("kugou", KUGOU_WEIGHT),
                        "bilibili", effectiveWeight("bilibili", BILI_WEIGHT)));
    }

    public List<SongDTO> getRandomSongs(int count) {
        count = Math.max(1, Math.min(count, MAX_RANDOM_COUNT));
        List<SongDTO> songs;
        try {
            // 每次随机挑一个关键词，单次上游搜索，多次调用自然覆盖更大曲库。
            String keyword = RANDOM_KEYWORDS.get(ThreadLocalRandom.current().nextInt(RANDOM_KEYWORDS.size()));
            songs = new ArrayList<>(search(keyword, 1, 30, null).getList());
        } catch (Exception e) {
            log.warn("[RANDOM] 搜索随机关键词失败，降级到 DB 随机: {}", e.getMessage(), e);
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

    // ==================== QQ 熔断器（状态机见 QqCircuitBreaker） ====================

    /** 开路期间是否应跳过 QQ 分支（半开时仅放行一个探针）。 */
    boolean shouldSkipQq() {
        return qqBreaker.shouldSkip();
    }

    /** QQ 成功：清零计数、闭路（探针成功同样闭路）。 */
    void recordQqSuccess() {
        qqBreaker.recordSuccess();
    }

    /** QQ 失败/超时：累计；达阈值开路，探针失败则重开并重置计时。 */
    void recordQqFailure() {
        qqBreaker.recordFailure();
    }

    /** 单次搜索内恰好计数一次（实现见 PlatformSearchAdapter）。 */
    void recordQqOutcomeOnce(AtomicBoolean claimed, boolean success) {
        platformSearch.recordQqOutcomeOnce(claimed, success);
    }

    /** QQ 专属超时等待（实现见 PlatformSearchAdapter；保留本入口给同包测试验证 2s 预算）。 */
    List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed) {
        return platformSearch.getQqWithTimeout(future, claimed);
    }

    /** 解析译名/别名（实现见 SongDtoParser；保留本入口给同包测试）。 */
    static List<String> parseAlias(Object raw) {
        return SongDtoParser.parseAlias(raw);
    }

    /** B 站封面 CDN 判定（实现见 SongDtoParser）。 */
    static boolean isBiliCdnCover(String coverUrl) {
        return SongDtoParser.isBiliCdnCover(coverUrl);
    }

    /** 剩余预算秒数（实现见 PlatformSearchAdapter；保留本入口给同包测试）。 */
    static int remainingSec(long deadlineMs, int capSec) {
        return PlatformSearchAdapter.remainingSec(deadlineMs, capSec);
    }
}
