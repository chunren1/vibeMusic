package com.vibemusic.service;

import com.vibemusic.common.utils.ClientErrorScrubber;
import com.vibemusic.config.AudioQualityTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网易云取链与探测（从 SongPlayService 抽出，行为不变）。
 *
 * <p>职责边界：只负责「向网易云要一条可播链接」——per-user/共享 Cookie 取链、
 * need-login 与 VIP 权限受限的共享 Cookie 快切、BYOC 失效探测、多音质并行探测。
 * 平台选择、跨平台降级与 DB 兜底仍在 facade。
 *
 * <p>所有方法都不抛异常：上游失败一律返回原响应/null，由调用方按降级链处理。
 */
@Slf4j
class NeteaseUrlProbe {

    /** VIP 能力快切冷却窗口（毫秒）：并发取链与短时重复请求共享，避免快切风暴 */
    private static final long VIP_ROTATE_COOLDOWN_MS = 30_000;

    /**
     * 试听片段判定阈值（毫秒）：时长 ≤ 30 秒视为试听片段。
     * 探测与跨平台降级链共用同一阈值，故包内可见。
     */
    static final int TRIAL_DURATION_MS = 30000;

    private final NeteaseApiService neteaseApiService;
    private final ThreadPoolTaskExecutor getUrlExecutor;
    /** 最近一次 VIP 能力快切时间戳（见 {@link #tryAcquireVipRotateWindow}） */
    private final AtomicLong lastVipRotateAt = new AtomicLong(0);
    private volatile Counter needLoginCounter;
    private volatile Counter rotateCounter;
    private UserService userService;

    NeteaseUrlProbe(NeteaseApiService neteaseApiService, ThreadPoolTaskExecutor getUrlExecutor) {
        this.neteaseApiService = neteaseApiService;
        this.getUrlExecutor = getUrlExecutor;
    }

    /** BYOC 失效标记依赖 UserService；单元测试无 Spring 上下文时为 null，探测直接跳过。 */
    void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** need-login / 快切计数器注册（名称与描述保持不变）。 */
    void setMeterRegistry(MeterRegistry meterRegistry) {
        needLoginCounter = Counter.builder("play.netease.need_login")
                .description("网易云取链 need-login 信号次数").register(meterRegistry);
        rotateCounter = Counter.builder("play.netease.cookie_rotate")
                .description("共享 Cookie 快切触发次数").register(meterRegistry);
    }

    Map<String, Object> fetchNeteaseUrl(String sourceId, String level, String userCookie, Long userId) {
        Map<String, Object> body = userCookie != null
                ? neteaseApiService.getSongUrl(sourceId, level, userCookie)
                : neteaseApiService.getSongUrl(sourceId, level);
        observeNeteaseUrlPayload(body, userCookie, userId);
        // BYOC（per-user）路径不参与共享快切，语义保持不变
        if (userCookie != null) return body;
        if (NeteaseApiService.isNeedLoginPayload(body)) {
            if (needLoginCounter != null) needLoginCounter.increment();
            try {
                if (rotateCounter != null) rotateCounter.increment();
                if (neteaseApiService.rotateSharedCookie()) {
                    return neteaseApiService.getSongUrl(sourceId, level);
                }
            } catch (Exception e) {
                log.warn("共享 Cookie 快切重试失败（降级链不变）: {}", ClientErrorScrubber.scrub(e.getMessage()), e);
            }
        }
        return rotateSharedCookieOnVipBlock(sourceId, level, body);
    }

    /**
     * VIP 权限受限（付费曲目但当前共享 Cookie 无播放权限）→ 能力感知快切 + 重试一次。
     *
     * <p>与 need-login 分支的区别：上游仍是 {@code code=200}（登录态有效、搜索正常），
     * 只是 VIP-only 曲目只下发 ≤30s 试听（判定见 {@link NeteaseApiService#isVipBlockedPayload}），
     * 故必须按条目字段判定，并让网关重探"登录 + 播放能力"后换槽位。
     *
     * <p>仅共享链路（userCookie==null）参与；重试结果必须是<b>完整可播</b>才采信，
     * 否则保留原响应继续降级链（绝不抛错，绝不改变降级链形状）。
     * 并发取链（多音质并行）共用冷却窗口，避免风暴式快切；
     * 冷却内命中只跳过快切，不影响本次取链结果的降级处理。
     */
    private Map<String, Object> rotateSharedCookieOnVipBlock(String sourceId, String level, Map<String, Object> body) {
        if (!NeteaseApiService.isVipBlockedPayload(body)) return body;
        if (!tryAcquireVipRotateWindow()) return body;
        try {
            if (rotateCounter != null) rotateCounter.increment();
            if (!neteaseApiService.rotateSharedCookie(true)) return body;
            Map<String, Object> retried = neteaseApiService.getSongUrl(sourceId, level);
            if (NeteaseApiService.isFullPlayablePayload(retried)) {
                log.info("VIP 权限受限快切成功: {} level={} 已取得完整可播链接", ClientErrorScrubber.scrub(sourceId), level);
                return retried;
            }
            log.info("VIP 权限受限快切后重试仍不可播: {} level={}，保留原响应走降级链", ClientErrorScrubber.scrub(sourceId), level);
        } catch (Exception e) {
            log.warn("VIP 权限受限快切重试失败（降级链不变）: {}", ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return body;
    }

    /** 抢占 VIP 能力快切窗口（进程内冷却）：并发/短时重复请求只放行一次，避免快切风暴。 */
    private boolean tryAcquireVipRotateWindow() {
        long now = System.currentTimeMillis();
        long last = lastVipRotateAt.get();
        if (now - last < VIP_ROTATE_COOLDOWN_MS) return false;
        return lastVipRotateAt.compareAndSet(last, now);
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
            log.warn("BYOC 失效标记失败: userId={}, error={}", userId, ClientErrorScrubber.scrub(e.getMessage()));
        }
    }

    Map<String, Object> fetchNeteaseSearch(String keyword, int limit, String userCookie) {
        return userCookie != null
                ? neteaseApiService.searchNetease(keyword, limit, userCookie)
                : neteaseApiService.searchNetease(keyword, limit);
    }

    /**
     * 网易云并行探测（生产唯一取链路径，单一事实源）。
     * 按 [levels] 优先级检查并行提交的取链结果：跳过试听片段，返回首个可用 URL。
     * 预算统一由 [deadline] 约束（生产路径原先各自硬编码 5s 等待，与 8s 预算脱节）。
     */
    @SuppressWarnings("unchecked")
    NeteaseProbe probeNeteaseParallel(String sourceId, String[] levels, long deadline,
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
                log.warn("音质[{}] 歌曲 {} 取链池过载，直接跳过该级别", level, ClientErrorScrubber.scrub(sourceId));
            }
        }
        Map<String, Object> standardProbe = null;
        int trialCount = 0;
        try {
            for (String level : levels) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("音质降级链超时: {}, 已检查至 {}", ClientErrorScrubber.scrub(sourceId), level);
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
                        log.info("音质[{}] 歌曲 {} 为试听片段 → 尝试下一级", level, ClientErrorScrubber.scrub(sourceId));
                        continue;
                    }
                    return new NeteaseProbe(url, level, standardProbe, trialCount);
                } catch (TimeoutException e) {
                    log.info("音质[{}] 歌曲 {} 超时 → 尝试下一级", level, ClientErrorScrubber.scrub(sourceId));
                } catch (Exception e) {
                    log.warn("音质[{}] 歌曲 {} 异常: {} → 尝试下一级", level, ClientErrorScrubber.scrub(sourceId), ClientErrorScrubber.scrub(e.getMessage()), e);
                }
            }
        } finally {
            cancelUnfinished(futures.values());
        }
        return new NeteaseProbe(null, null, standardProbe, trialCount);
    }

    /**
     * 取消未完成的并行取链任务。
     * <p>
     * 语义说明（CompletableFuture.cancel(true) 无法中断已开始的任务，
     * boolean 参数对 CompletableFuture 无实质中断效果）：
     * <ul>
     *   <li>排队中尚未开始的任务：cancel 后不再执行，不消耗线程与上游配额；</li>
     *   <li>已开始执行的任务：无法被中断，会执行至返回/超时，但其结果会被调用方丢弃。</li>
     * </ul>
     * loser 并发被天然限制在 getUrl 池大小（8 线程 + 16 队列，AbortPolicy 快速失败）
     * 与整体 8s DEADLINE 之内，不会 wedge 线程池。
     */
    private static void cancelUnfinished(Collection<CompletableFuture<Map<String, Object>>> futures) {
        for (CompletableFuture<Map<String, Object>> f : futures) {
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /** 网易云并行探测结果：命中 URL 与级别（可空）、standard 原始响应（试听兜底复用）、试听片段命中次数。 */
    static final class NeteaseProbe {
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
}
