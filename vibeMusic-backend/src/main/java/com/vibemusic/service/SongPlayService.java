package com.vibemusic.service;

import com.vibemusic.common.utils.ClientErrorScrubber;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲播放服务
 * <p>
 * 音质降级链：MinIO 缓存 → 原平台 API（多级音质）→ 跨平台降级 → 跨平台换源 → DB 历史 URL 兜底
 * <p>
 * 线程池由 Spring 托管（ThreadPoolConfig），避免 static ExecutorService 泄漏。
 * <p>
 * 拆分（P1-8）：本类只保留编排——平台选择分支、8s 预算、MinIO 命中直返、DB 兜底、
 * 降级计数与指标注册。网易云取链/探测见 {@link NeteaseUrlProbe}，平台直取见
 * {@link PlatformUrlFetcher}，跨平台降级见 {@link NeteaseDegradeChain}，
 * 404 前的按名换源见 {@link CrossPlatformFailover}。
 */
@Slf4j
@Service
public class SongPlayService {

    private final SongMapper songMapper;
    private final StorageService storageService;
    /** 网易云取链与并行探测。 */
    private final NeteaseUrlProbe neteaseUrlProbe;
    /** 平台直取（B站/酷狗/咪咕/QQ）与 MinIO 缓存判定。 */
    private final PlatformUrlFetcher platformUrlFetcher;
    /** 跨平台降级链（网易→QQ / 其它→网易云）。 */
    private final NeteaseDegradeChain degradeChain;
    /** DB 兜底之前的最后一道：按名去未试过的平台换源（点歌 404 兜底）。 */
    private final CrossPlatformFailover crossPlatformFailover;

    private final AtomicInteger degradationCount = new AtomicInteger(0);
    /** 换源命中计数：独立于 degradationCount（语义不同，勿混算）。 */
    private final AtomicInteger failoverCount = new AtomicInteger(0);

    /**
     * 本次请求换源命中的平台，仅用于给流代理补 {@code X-Play-Source} 响应头。
     * ThreadLocal 必须一次性消费（{@link #consumeFailoverPlaySource()}），否则线程复用会串请求。
     */
    private final ThreadLocal<String> failoverPlaySource = new ThreadLocal<>();

    private UserService userService;

    /** 播放链接短缓存；可选注入（既有单测直接 new 本类，不走 Spring 上下文）。 */
    private PlayUrlCache playUrlCache;

    public SongPlayService(SongMapper songMapper,
                          NeteaseApiService neteaseApiService,
                          StorageService storageService,
                          StringRedisTemplate stringRedisTemplate,
                          ThreadPoolTaskExecutor getUrlExecutor) {
        this.songMapper = songMapper;
        this.storageService = storageService;
        this.neteaseUrlProbe = new NeteaseUrlProbe(neteaseApiService, getUrlExecutor);
        this.platformUrlFetcher = new PlatformUrlFetcher(neteaseApiService, storageService, stringRedisTemplate);
        this.degradeChain = new NeteaseDegradeChain(
                songMapper, neteaseApiService, neteaseUrlProbe, degradationCount, getUrlExecutor);
        this.crossPlatformFailover = new CrossPlatformFailover(
                neteaseApiService, platformUrlFetcher, getUrlExecutor, failoverCount);
    }

    @Autowired(required = false)
    public void setUserService(UserService userService) {
        this.userService = userService;
        // BYOC 失效探测在探测类内完成，需同步注入（测试无 Spring 上下文时为 null）
        neteaseUrlProbe.setUserService(userService);
    }

    /** 可选注入：单测无 Spring 上下文时为 null，此时退化为「无缓存」直取。 */
    @Autowired(required = false)
    public void setPlayUrlCache(PlayUrlCache playUrlCache) {
        this.playUrlCache = playUrlCache;
    }

    /**
     * 降级计数接入 Prometheus（此前只写不读，属墓碑计数器）。
     * 可选注入：单元测试无 Spring 上下文时为 null，跳过注册。
     */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            meterRegistry.gauge("play.degradation.count", degradationCount, AtomicInteger::get);
            // 换源命中数独立注册：与降级计数语义不同（换平台换歌 vs 同歌换链路）
            meterRegistry.gauge("play.failover.count", failoverCount, AtomicInteger::get);
            neteaseUrlProbe.setMeterRegistry(meterRegistry);
        }
    }

    /** 当前用户的网易云 Cookie；解析逻辑收敛在 UserService（单一事实源）。 */
    private String resolveUserCookie() {
        return userService == null ? null : userService.resolveCurrentNeteaseCookie();
    }

    public int getDegradationCount() { return degradationCount.get(); }

    /** 换源命中次数（独立口径，勿与 {@link #getDegradationCount()} 混算）。 */
    public int getFailoverCount() { return failoverCount.get(); }

    /**
     * 一次性读取「本次请求是否换源命中」，供流代理写 {@code X-Play-Source} 响应头。
     * 读到即清（ThreadLocal 不得跨请求残留）。
     */
    public String consumeFailoverPlaySource() {
        String platform = failoverPlaySource.get();
        failoverPlaySource.remove();
        return platform;
    }

    public String getPlayUrl(String sourceId) {
        return getPlayUrl(sourceId, null, null, null);
    }

    public String getPlayUrl(String sourceId, String songName, String artist) {
        return getPlayUrl(sourceId, songName, artist, null);
    }

    public String getPlayUrl(String sourceId, String songName, String artist, String platform) {
        return getPlayUrl(sourceId, songName, artist, platform, resolveUserCookie());
    }

    /**
     * 取链外层总预算（8s）。必须严格大于内层 HTTP 响应超时
     * {@link com.vibemusic.config.RestTemplateConfig#RESPONSE_TIMEOUT}（5s），
     * 否则单次上游调用就会吃穿整条链路；包内可见以便超时预算自洽测试断言。
     */
    static final long PLAY_BUDGET_MS = 8000;

    public String getPlayUrl(String sourceId, String songName, String artist, String platform, String userCookie) {
        // 短缓存：同一会话内的连续 Range 请求（拖动进度 / 断线重连）不再重跑整条降级链。
        // 详见 PlayUrlCache 类注释（TTL 取舍、跨用户隔离、403 失效自愈）。
        // 先清 ThreadLocal：缓存命中路径不会走 resolvePlayUrl 的那句 remove，
        // 不清的话上一请求残留的换源平台会串到本次响应头（线程复用）。
        failoverPlaySource.remove();
        final Long callerUserId = UserService.getCurrentUserId();
        final String userScope = callerUserId == null ? null : callerUserId.toString();
        if (playUrlCache != null) {
            PlayUrlCache.Entry cached = playUrlCache.get(userScope, platform, sourceId);
            if (cached != null) {
                // 换源命中的平台一并缓存：命中时仍要能补 X-Play-Source 头
                if (cached.src() != null) failoverPlaySource.set(cached.src());
                return cached.url();
            }
        }
        String url = resolvePlayUrl(sourceId, songName, artist, platform, userCookie);
        if (playUrlCache != null && url != null) {
            playUrlCache.put(userScope, platform, sourceId, url, failoverPlaySource.get());
        }
        return url;
    }

    /**
     * 上游 CDN 返回 403/404（签名 URL 过期）时作废缓存，供 {@link AudioProxyService} 在重试前调用。
     * 不作废的话 5 次重试会拿到同一个死 URL，把可自愈路径变成必死 500。
     */
    public void invalidatePlayUrl(String sourceId, String platform) {
        if (playUrlCache == null) return;
        Long callerUserId = UserService.getCurrentUserId();
        playUrlCache.invalidate(callerUserId == null ? null : callerUserId.toString(), platform, sourceId);
    }

    /** 实际取链（原 {@code getPlayUrl} 本体，缓存层在外层同名方法）。 */
    private String resolvePlayUrl(String sourceId, String songName, String artist, String platform, String userCookie) {
        // 请求线程捕获 userId（异步取链线程读不到 SecurityContext）。
        final Long callerUserId = UserService.getCurrentUserId();
        // 生产链路整体 8s 超时保护（内层 HTTP 5s，见 PLAY_BUDGET_MS 注释）
        final long DEADLINE = System.currentTimeMillis() + PLAY_BUDGET_MS;
        // 上一请求若在异常路径上残留了换源头，先清掉，避免线程复用串到本次响应
        failoverPlaySource.remove();
        // 本次请求「已试过」的平台集合：换源只去没试过的平台，绝不重试（含当前平台与降级链目标）
        Set<String> triedPlatforms = new HashSet<>();
        // 1. 优先检查 MinIO 缓存（Redis 缓存 exists 结果，TTL 10min）
        String minioObjectName = "songs/" + sourceId + ".mp3";
        if (platformUrlFetcher.isCachedInMinio(sourceId, userCookie)) {
            String directUrl = storageService.getDirectUrl(minioObjectName);
            log.info("getPlayUrl: {} 命中 MinIO 缓存", ClientErrorScrubber.scrub(sourceId));
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
                triedPlatforms.add("migu");
                triedPlatforms.add("netease");
                String miguUrl = platformUrlFetcher.fetchMiguUrl(sourceId);
                if (miguUrl != null) return miguUrl;
                log.info("getPlayUrl: Migu歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitKugou || (!explicitQQ && !explicitNE && PlatformUrlFetcher.isKugouHash(sourceId))) {
                // 酷狗：platform=kugou 显式指定，或无显式平台时的 32 位 hash 猜测
                // （此前误入 QQ 分支）；albumId 暂无法透传，取链失败则走网易云降级
                triedPlatforms.add("kugou");
                triedPlatforms.add("netease");
                String kugouUrl = platformUrlFetcher.tryKugouUrl(sourceId);
                if (kugouUrl != null) return kugouUrl;
                log.info("getPlayUrl: Kugou歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitBili || (!explicitQQ && !explicitNE && !explicitMigu && !explicitKugou && PlatformUrlFetcher.isBiliId(sourceId))) {
                // B站：platform=bilibili 显式指定，或无显式平台时的 BV 猜测
                // （BV 形绝不能落入 QQ 分支，故本分支置于 QQ 分支之前）
                triedPlatforms.add("bilibili");
                triedPlatforms.add("netease");
                String biliUrl = platformUrlFetcher.tryBiliUrl(sourceId);
                if (biliUrl != null) return biliUrl;
                log.info("getPlayUrl: Bili歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitQQ || (!explicitNE && !guessNetEase)) {
                // 取链实现与其它平台分支共用 fetchQqUrl（单一事实源）
                triedPlatforms.add("qq");
                triedPlatforms.add("netease");
                String qqUrl = platformUrlFetcher.fetchQqUrl(sourceId);
                if (qqUrl != null) return qqUrl;
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else {
                // 并行探测 3 级音质（exhigh/higher/standard），取最高可用非试听版本
                // 探测实现与其它平台分支共用 probeNeteaseParallel（单一事实源）
                triedPlatforms.add("netease");
                String[] levels = {"exhigh", "higher", "standard"};
                NeteaseUrlProbe.NeteaseProbe probe =
                        neteaseUrlProbe.probeNeteaseParallel(sourceId, levels, DEADLINE, userCookie, callerUserId);
                if (probe.url != null) return probe.url;
                // 版权预检 fast-fail：standard 明确硬拦截（url 空、无试听信息且非付费曲目）
                // 时跳过 QQ 降级；need-login/试听/VIP 权限受限/空响应一律走完整降级链。
                //
                // 注意（本次改动）：fast-fail 只跳过「网易→QQ 降级」这一步，不跳过跨平台换源——
                // 网易明确没版权恰恰是该去咪咕/酷狗/B站找的场景（见下方 2.5）。QQ 之所以同时计入
                // triedPlatforms：fast-fail 的原意就是「明确无版权时不该白跑一遍 QQ」，若换源再把 QQ
                // 跑一遍，fast-fail 就退化成纯换序、省不下任何时间，原语义会被改坏。
                NeteaseApiService.CopyrightPrecheck precheck =
                        NeteaseApiService.precheckUrlPayload(probe.standardProbe);
                if (precheck == NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT) {
                    log.info("版权预检硬拦截: {} 无可用版权，跳过QQ降级（仍尝试跨平台换源）", ClientErrorScrubber.scrub(sourceId));
                } else {
                    if (precheck == NeteaseApiService.CopyrightPrecheck.PERMISSION_BLOCKED) {
                        log.info("VIP 权限受限: {} 付费曲目当前共享 Cookie 无播放权限（非无版权），继续 QQ 降级", ClientErrorScrubber.scrub(sourceId));
                    }
                    // 网易云无可用链接（全失败或全试听）→ QQ 降级（Q2d 收敛）
                    String qqUrl = degradeChain.degradeNeteaseToQq(songName, artist, sourceId, DEADLINE);
                    if (qqUrl != null) return qqUrl;
                }
                // QQ 降级已被 fast-fail 有意跳过或已执行过 → 换源不再重复 QQ
                triedPlatforms.add("qq");
                log.info("歌曲 {} 当前平台与降级链均无可用链接，进入跨平台换源", ClientErrorScrubber.scrub(sourceId));
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试DB兜底: {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()), e);
        }

        // 2.5 跨平台换源（DB 兜底之前的最后一道）：
        //     既有降级链每平台只降级到网易云，网易云也没搜到就直接 404（2026-10-25 线上实测：
        //     platform=qq 的 VIP 曲「搜到了但点了没反应」）。这里按「歌名 + 歌手」去本次请求
        //     尚未试过的平台（咪咕→酷狗→B站→QQ）再找一条可播链接，自身预算 3s。
        //     放在 DB 兜底之前：DB 只存历史 url 列，可能已过期或本来就为空；换源能拿到新签名链接，
        //     而 DB 兜底保留为真正的最终兜底。失败语义不变：仍返回 null → controller 404。
        if (songName != null && !songName.isBlank()) {
            CrossPlatformFailover.Result failover =
                    crossPlatformFailover.tryFailover(songName, artist, triedPlatforms, DEADLINE);
            if (failover != null) {
                // 供流代理补 X-Play-Source 响应头（仅新增头，不改信封/响应体）
                failoverPlaySource.set(failover.platform());
                return failover.url();
            }
        }
        log.warn("歌曲 {} 所有平台均无可用播放链接，走DB兜底", ClientErrorScrubber.scrub(sourceId));

        // 3. API 失败 → 从 DB 兜底（只查 url 列）
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                .eq(Song::getSourceId, sourceId)
                .select(Song::getId, Song::getUrl));
        return song != null ? song.getUrl() : null;
    }
}
