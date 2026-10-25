package com.vibemusic.service;

import com.vibemusic.common.utils.ClientErrorScrubber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 跨平台换源（点歌 404 前的最后一道，位于 DB 兜底之前）。
 *
 * <p>解决的问题（2026-10-25 线上实测）：搜索首条 {@code platform=qq} 的曲目在 QQ 无可用链
 * （VIP/无版权），既有降级链只降级到网易云，网易云也没搜到 → 直接 404，用户视角是
 * 「搜到了但点了没反应」。本类按「歌名 + 歌手」去<b>尚未试过</b>的平台再找一条可播链接。
 *
 * <p>职责边界：只做「按名搜索 → 取最佳候选 → 向该平台要一条 URL」；
 * 不选择平台顺序之外的事、不碰 DB、不碰 MinIO、<b>绝不回头调用
 * {@code SongPlayService#getPlayUrl}</b>（避免递归/死循环，本类也不持有它的引用）。
 * 任何上游失败都只返回 null，绝不抛错——失败语义保持「全失败 → null → controller 404」。
 *
 * <p>顺序与预算：
 * <ul>
 *   <li>顺序固定 {@link #PLATFORM_ORDER}：咪咕 → 酷狗 → B站 → QQ。网易云不在此列——
 *       它由 {@link NeteaseDegradeChain} 专门负责（网易→QQ 及 其它平台→网易），
 *       这里重复只会白烧预算。</li>
 *   <li>外层总预算由 {@code SongPlayService.PLAY_BUDGET_MS}（8s）传入，本类自身最多花
 *       {@link #FAILOVER_BUDGET_MS}（3s）：取两者较小值作为本次换源 deadline，
 *       每试一个平台前先查剩余预算，不足立刻放弃，避免把 404 拖成 8s 卡死。</li>
 * </ul>
 *
 * <p>可观测：每个平台一次 {@code log.info}（命中/未命中），sourceId 一律过
 * {@link ClientErrorScrubber#scrub}；Cookie 不进入本类，日志中也不会出现。
 * 命中计数使用<b>独立</b>的 {@code failoverCount}（Prometheus {@code play.failover.count}），
 * 不与 {@code degradationCount}（原有音质/平台降级计数）混算——两者语义不同：
 * 前者=「换到了另一个平台的另一首歌」，后者=「同一首歌换了一条链路」。
 */
@Slf4j
class CrossPlatformFailover {

    /** 换源自身预算上限（3s）：必须远小于外层 8s，失败时不能把 404 变成长时间卡死。 */
    static final long FAILOVER_BUDGET_MS = 3_000L;

    /** 换源顺序：咪咕 → 酷狗 → B站 → QQ（网易云由 NeteaseDegradeChain 负责，不重复）。 */
    static final List<String> PLATFORM_ORDER = List.of("migu", "kugou", "bilibili", "qq");

    /** 每平台搜索候选数：与既有降级链一致（searchXxx(keyword, 5)）。 */
    private static final int SEARCH_LIMIT = 5;

    private final NeteaseApiService neteaseApiService;
    private final PlatformUrlFetcher platformUrlFetcher;
    private final ThreadPoolTaskExecutor getUrlExecutor;
    /** 独立命中计数（不混入 degradationCount），由 facade 注册为 Prometheus gauge。 */
    private final AtomicInteger hitCount;
    /** 可注入时钟：预算判定全部走它，单测可用固定时钟断言「预算耗尽立刻放弃」而无需 sleep。 */
    private final LongSupplier clock;

    CrossPlatformFailover(NeteaseApiService neteaseApiService,
                          PlatformUrlFetcher platformUrlFetcher,
                          ThreadPoolTaskExecutor getUrlExecutor,
                          AtomicInteger hitCount) {
        this(neteaseApiService, platformUrlFetcher, getUrlExecutor, hitCount, System::currentTimeMillis);
    }

    /** 包内可见：单测注入固定时钟。 */
    CrossPlatformFailover(NeteaseApiService neteaseApiService,
                          PlatformUrlFetcher platformUrlFetcher,
                          ThreadPoolTaskExecutor getUrlExecutor,
                          AtomicInteger hitCount,
                          LongSupplier clock) {
        this.neteaseApiService = neteaseApiService;
        this.platformUrlFetcher = platformUrlFetcher;
        this.getUrlExecutor = getUrlExecutor;
        this.hitCount = hitCount;
        this.clock = clock;
    }

    /** 命中结果：url 供播放，platform 供 {@code X-Play-Source} 响应头（前端提示「已自动切换」）。 */
    record Result(String url, String platform) {
    }

    /** 平台名归一：网关/前端可能传 {@code bili}，统一成 {@link #PLATFORM_ORDER} 里的 {@code bilibili}。 */
    static String normalizePlatform(String platform) {
        if (platform == null) return null;
        String p = platform.trim().toLowerCase();
        return "bili".equals(p) ? "bilibili" : p;
    }

    /**
     * 换源唯一入口：在未试过的平台里找一条可播链接。
     *
     * @param songName       歌名；为空/空白直接跳过（没名字搜不出东西，且避免空关键词打上游）
     * @param artist         歌手；可为空（关键词退化为纯歌名）
     * @param triedPlatforms 本次请求已试过的平台（含当前平台与降级链已跑过的平台），不重试
     * @param outerDeadline  外层总 deadline（{@code SongPlayService.PLAY_BUDGET_MS} 计算）
     * @return 命中返回 {@link Result}；全部失败/预算不足/无歌名返回 null
     */
    Result tryFailover(String songName, String artist, Set<String> triedPlatforms, long outerDeadline) {
        if (songName == null || songName.isBlank()) {
            log.debug("换源跳过: 缺少歌名，无法按名搜索");
            return null;
        }
        final Set<String> tried = normalizeAll(triedPlatforms);
        final long deadline = Math.min(outerDeadline, clock.getAsLong() + FAILOVER_BUDGET_MS);
        final String keyword = buildKeyword(songName, artist);

        for (String platform : PLATFORM_ORDER) {
            if (tried.contains(platform)) {
                log.debug("换源跳过: 平台 {} 本次请求已试过", platform);
                continue;
            }
            // 逐次检查剩余预算：预算不足立刻放弃，绝不把 404 拖成超时
            long left = deadline - clock.getAsLong();
            if (left <= 0) {
                log.info("换源中止: 预算耗尽（上限 {}ms），未试平台 {} 放弃: '{}'",
                        FAILOVER_BUDGET_MS, platform, ClientErrorScrubber.scrub(keyword));
                return null;
            }
            String url = callWithDeadline(() -> tryPlatform(platform, songName, artist, keyword), deadline);
            if (url != null) {
                hitCount.incrementAndGet();
                log.info("换源成功: '{}' 由平台 {} 提供可播链接（原平台 {} 无可用链）",
                        ClientErrorScrubber.scrub(keyword), platform, tried);
                return new Result(url, platform);
            }
            log.info("换源未命中: 平台 {} 无可播链接: '{}'", platform, ClientErrorScrubber.scrub(keyword));
        }
        log.info("换源全平台未命中: '{}'（已试 {}）", ClientErrorScrubber.scrub(keyword), tried);
        return null;
    }

    /** 单平台：搜索 → 选最佳候选 → 取链。任何异常向上抛给 callWithDeadline 统一兜成 null。 */
    private String tryPlatform(String platform, String songName, String artist, String keyword) {
        List<Map<String, Object>> candidates = extractData(search(platform, keyword));
        if (candidates.isEmpty()) return null;
        // tie-break：复用既有 SongMatcher（token 重叠率 + 歌名首字加权，全低分时退回上游第一条），
        // 与网易→QQ 降级链保持同一套「像不像同一首歌」的判定，不另起一套。
        Map<String, Object> best = SongMatcher.findBestMatch(candidates, songName, artist);
        if (best == null) return null;
        String candidateId = best.get("id") != null ? String.valueOf(best.get("id")) : null;
        if (candidateId == null || candidateId.isBlank()) return null;
        String matchName = best.get("name") != null ? String.valueOf(best.get("name")) : "";
        if (isTrialOnly(best)) {
            // 与降级链一致：<=30s 的候选只是试听片段，宁可换下一个平台也不给用户放 30s 截断
            log.info("换源: 平台 {} 候选 '{}' 仅试听版，跳过", platform, ClientErrorScrubber.scrub(matchName));
            return null;
        }
        String url = fetchUrl(platform, candidateId);
        if (url != null) {
            log.info("换源候选命中: 平台 {} id={} ('{}')",
                    platform, ClientErrorScrubber.scrub(candidateId), ClientErrorScrubber.scrub(matchName));
        }
        return url;
    }

    private String buildKeyword(String songName, String artist) {
        return artist != null && !artist.isBlank() ? songName + " " + artist : songName;
    }

    /** 按名搜索：只走已有 searchXxx，不新增网关端点。 */
    private Map<String, Object> search(String platform, String keyword) {
        return switch (platform) {
            case "migu" -> neteaseApiService.searchMigu(keyword, SEARCH_LIMIT);
            case "kugou" -> neteaseApiService.searchKugou(keyword, SEARCH_LIMIT);
            case "bilibili" -> neteaseApiService.searchBili(keyword, SEARCH_LIMIT);
            case "qq" -> neteaseApiService.searchQQ(keyword, SEARCH_LIMIT);
            default -> null;
        };
    }

    /**
     * 取链：只调 {@link PlatformUrlFetcher}（单一事实源），<b>绝不回头调
     * {@code SongPlayService#getPlayUrl}</b>——否则会与降级链形成递归。
     *
     * <p>咪咕传 null copyrightId：网关在 /migu/search 时已把 contentId→copyrightId 写入
     * 进程内缓存（musicapi src/search.js miguCopyrightCache），/migu/url 会回查该缓存；
     * 这也是 PlatformUrlFetcher#fetchMiguUrl 现有签名（单参数）能直接复用的原因。
     */
    private String fetchUrl(String platform, String candidateId) {
        return switch (platform) {
            case "migu" -> platformUrlFetcher.fetchMiguUrl(candidateId);
            case "kugou" -> platformUrlFetcher.tryKugouUrl(candidateId);
            case "bilibili" -> platformUrlFetcher.tryBiliUrl(candidateId);
            case "qq" -> platformUrlFetcher.fetchQqUrl(candidateId);
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractData(Map<String, Object> result) {
        if (result == null) return List.of();
        Object data = result.get("data");
        if (!(data instanceof List<?> list)) return List.of();
        List<Map<String, Object>> songs = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                songs.add((Map<String, Object>) map);
            }
        }
        return songs;
    }

    /** 候选时长 <= 30s 视为试听片段（与 NeteaseDegradeChain 同一阈值口径）。 */
    private boolean isTrialOnly(Map<String, Object> candidate) {
        Object durObj = candidate.get("duration");
        return durObj instanceof Number
                && ((Number) durObj).intValue() > 0
                && ((Number) durObj).intValue() <= NeteaseUrlProbe.TRIAL_DURATION_MS;
    }

    private Set<String> normalizeAll(Set<String> platforms) {
        if (platforms == null || platforms.isEmpty()) return Set.of();
        Set<String> normalized = new HashSet<>();
        for (String platform : platforms) {
            String p = normalizePlatform(platform);
            if (p != null) normalized.add(p);
        }
        return normalized;
    }

    /**
     * 在剩余预算内限时执行上游调用（与 {@link NeteaseDegradeChain} 同范式）：
     * 预算约束调用本身而非仅约束是否发起；预算耗尽/超时/异常一律返回 null，
     * 超时取消排队任务，避免慢源占住取链线程池。
     */
    private String callWithDeadline(Supplier<String> supplier, long deadline) {
        long left = deadline - clock.getAsLong();
        if (left <= 0) {
            log.info("换源限时调用跳过: 预算已耗尽");
            return null;
        }
        final CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(supplier, getUrlExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("换源取链池过载，跳过本次平台尝试");
            return null;
        }
        try {
            return future.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("换源限时调用超时: {}ms 预算内未返回，已取消", left);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("换源限时调用被中断，已取消");
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("换源上游异常: {}", ClientErrorScrubber.scrub(cause.getMessage()), cause);
            return null;
        }
    }
}
