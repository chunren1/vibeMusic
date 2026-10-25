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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲播放服务
 * <p>
 * 音质降级链：MinIO 缓存 → 原平台 API（多级音质）→ 跨平台降级 → DB 历史 URL 兜底
 * <p>
 * 线程池由 Spring 托管（ThreadPoolConfig），避免 static ExecutorService 泄漏。
 * <p>
 * 拆分（P1-8）：本类只保留编排——平台选择分支、8s 预算、MinIO 命中直返、DB 兜底、
 * 降级计数与指标注册。网易云取链/探测见 {@link NeteaseUrlProbe}，平台直取见
 * {@link PlatformUrlFetcher}，跨平台降级见 {@link NeteaseDegradeChain}。
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

    private final AtomicInteger degradationCount = new AtomicInteger(0);

    private UserService userService;

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
    }

    @Autowired(required = false)
    public void setUserService(UserService userService) {
        this.userService = userService;
        // BYOC 失效探测在探测类内完成，需同步注入（测试无 Spring 上下文时为 null）
        neteaseUrlProbe.setUserService(userService);
    }

    /**
     * 降级计数接入 Prometheus（此前只写不读，属墓碑计数器）。
     * 可选注入：单元测试无 Spring 上下文时为 null，跳过注册。
     */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            meterRegistry.gauge("play.degradation.count", degradationCount, AtomicInteger::get);
            neteaseUrlProbe.setMeterRegistry(meterRegistry);
        }
    }

    /** 当前用户的网易云 Cookie；解析逻辑收敛在 UserService（单一事实源）。 */
    private String resolveUserCookie() {
        return userService == null ? null : userService.resolveCurrentNeteaseCookie();
    }

    public int getDegradationCount() { return degradationCount.get(); }

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
        // 请求线程捕获 userId（异步取链线程读不到 SecurityContext）。
        final Long callerUserId = UserService.getCurrentUserId();
        // 生产链路整体 8s 超时保护（内层 HTTP 5s，见 PLAY_BUDGET_MS 注释）
        final long DEADLINE = System.currentTimeMillis() + PLAY_BUDGET_MS;
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
                String miguUrl = platformUrlFetcher.fetchMiguUrl(sourceId);
                if (miguUrl != null) return miguUrl;
                log.info("getPlayUrl: Migu歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitKugou || (!explicitQQ && !explicitNE && PlatformUrlFetcher.isKugouHash(sourceId))) {
                // 酷狗：platform=kugou 显式指定，或无显式平台时的 32 位 hash 猜测
                // （此前误入 QQ 分支）；albumId 暂无法透传，取链失败则走网易云降级
                String kugouUrl = platformUrlFetcher.tryKugouUrl(sourceId);
                if (kugouUrl != null) return kugouUrl;
                log.info("getPlayUrl: Kugou歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitBili || (!explicitQQ && !explicitNE && !explicitMigu && !explicitKugou && PlatformUrlFetcher.isBiliId(sourceId))) {
                // B站：platform=bilibili 显式指定，或无显式平台时的 BV 猜测
                // （BV 形绝不能落入 QQ 分支，故本分支置于 QQ 分支之前）
                String biliUrl = platformUrlFetcher.tryBiliUrl(sourceId);
                if (biliUrl != null) return biliUrl;
                log.info("getPlayUrl: Bili歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else if (explicitQQ || (!explicitNE && !guessNetEase)) {
                // 取链实现与其它平台分支共用 fetchQqUrl（单一事实源）
                String qqUrl = platformUrlFetcher.fetchQqUrl(sourceId);
                if (qqUrl != null) return qqUrl;
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", ClientErrorScrubber.scrub(sourceId));
                String neteaseUrl = degradeChain.tryNeteaseFallback(songName, artist, sourceId, userCookie, callerUserId);
                if (neteaseUrl != null) return neteaseUrl;
            } else {
                // 并行探测 3 级音质（exhigh/higher/standard），取最高可用非试听版本
                // 探测实现与其它平台分支共用 probeNeteaseParallel（单一事实源）
                String[] levels = {"exhigh", "higher", "standard"};
                NeteaseUrlProbe.NeteaseProbe probe =
                        neteaseUrlProbe.probeNeteaseParallel(sourceId, levels, DEADLINE, userCookie, callerUserId);
                if (probe.url != null) return probe.url;
                // 版权预检 fast-fail：standard 明确硬拦截（url 空、无试听信息且非付费曲目）
                // 时跳过 QQ 降级、直落 DB 兜底；need-login/试听/VIP 权限受限/空响应一律走完整降级链
                NeteaseApiService.CopyrightPrecheck precheck =
                        NeteaseApiService.precheckUrlPayload(probe.standardProbe);
                if (precheck == NeteaseApiService.CopyrightPrecheck.NO_COPYRIGHT) {
                    log.info("版权预检硬拦截: {} 无可用版权，跳过QQ降级直落DB兜底", ClientErrorScrubber.scrub(sourceId));
                } else {
                    if (precheck == NeteaseApiService.CopyrightPrecheck.PERMISSION_BLOCKED) {
                        log.info("VIP 权限受限: {} 付费曲目当前共享 Cookie 无播放权限（非无版权），继续 QQ 降级", ClientErrorScrubber.scrub(sourceId));
                    }
                    // 网易云无可用链接（全失败或全试听）→ QQ 降级（Q2d 收敛）
                    String qqUrl = degradeChain.degradeNeteaseToQq(songName, artist, sourceId, DEADLINE);
                    if (qqUrl != null) return qqUrl;
                }
                log.warn("歌曲 {} 所有平台均无可用播放链接", ClientErrorScrubber.scrub(sourceId));
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试DB兜底: {}", ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()), e);
        }

        // 3. API 失败 → 从 DB 兜底（只查 url 列）
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                .eq(Song::getSourceId, sourceId)
                .select(Song::getId, Song::getUrl));
        return song != null ? song.getUrl() : null;
    }
}
