package com.vibemusic.common.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.function.LongSupplier;

/**
 * 网关（musicapi）整体熔断器 —— 解决「单平台熔断管不了网关整体不可用」的问题。
 *
 * <p>背景：{@code SongSearchService} 已有按平台的 QQ 熔断器，但当 musicapi 进程整体挂掉时，
 * 5 个平台会同时连接拒绝/超时，每次搜索都要 5 线程 × 最多 3s 空转，白占搜索线程与 DB 连接窗口。
 * 本类把「整个网关」作为一个熔断单元：连续 N 次<b>传输层</b>失败即开路，开路期间调用方跳过全部
 * 远程分支，只走本地兜底（空结果的 retry 提示），绝不返回 5xx。
 *
 * <p>计数口径（只认传输层）：
 * <ul>
 *   <li>计入：连接拒绝（{@link ConnectException}）、连接/读超时（{@link SocketTimeoutException}、
 *       {@link InterruptedIOException}、{@link ResourceAccessException}）、域名解析失败、
 *       HTTP 5xx（{@link RestClientResponseException} 且 {@code status.is5xxServerError()}）；</li>
 *   <li>不计入：HTTP 4xx（业务侧问题）、200 但结果为空、单条解析失败 —— 这些说明网关活着。</li>
 * </ul>
 *
 * <p>状态机（与既有 QQ 熔断风格一致，时钟可注入便于测试）：
 * <pre>
 *   闭路 --连续失败达阈值--> 开路 --窗口到期--> 半开(只放 1 个探针)
 *     ^                        |                    |
 *     +------- 任一调用成功 ----+----- 探针失败 -----+
 * </pre>
 *
 * <p>纯内存、无 Spring 依赖、线程安全；单实例部署假设（与 QQ 熔断器的内存态一致）。
 */
@Slf4j
public class UpstreamBreaker {

    /** 连续传输失败阈值 N=10：5 平台 fanout 下约等于「连续 2 次全平台失败」，既有灵敏度又不至于被单次抖动误开。 */
    public static final int DEFAULT_FAILURE_THRESHOLD = 10;

    /** 开路窗口 30s：与上游恢复时间同量级，短到能自动探测恢复，长到足以挡住故障期的空转洪峰。 */
    public static final long DEFAULT_OPEN_MS = 30_000L;

    /**
     * 半开探针最长占用 10s（> 搜索 fanout 预算 4s）。
     * 探针线程被取消/提前返回时可能来不及回传结果，超过此时长即视为放弃并放行新探针，
     * 保证熔断器不会被一个丢失的探针永久卡在半开。
     */
    static final long PROBE_ABANDON_MS = 10_000L;

    private final int failureThreshold;
    private final long openWindowMs;
    private final LongSupplier clock;

    /** 状态变更统一在此锁下进行（计数、开路时刻、半开探针占位）。 */
    private final Object lock = new Object();

    private int consecutiveFailures;
    /** 开路时刻毫秒；-1 = 闭路。 */
    private long openedAt = -1L;
    /** 半开探针占位：窗口到期后只允许 1 个请求穿透，其余继续跳过。 */
    private boolean probeInFlight;
    /** 探针占位起始时刻（用于丢弃丢失的探针）。 */
    private long probeStartedAt;

    /** 生产默认：阈值 10 次、窗口 30s、系统时钟。 */
    public UpstreamBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_MS, System::currentTimeMillis);
    }

    /** 测试用：可指定阈值/窗口/时钟（假时钟避免 sleep）。 */
    public UpstreamBreaker(int failureThreshold, long openWindowMs, LongSupplier clock) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold 必须 > 0");
        if (openWindowMs <= 0) throw new IllegalArgumentException("openWindowMs 必须 > 0");
        this.failureThreshold = failureThreshold;
        this.openWindowMs = openWindowMs;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 本次请求是否允许访问远程上游。
     *
     * <p>闭路 → true；开路 → false；开路窗口到期 → 只给 1 个请求发半开探针（true），其余 false。
     * 探针无论成功/失败都会在 {@link #recordSuccess()} / {@link #recordAttemptFailure(boolean)}
     * 中释放占位，避免探针卡死导致永久半开。
     */
    public boolean allow() {
        synchronized (lock) {
            if (openedAt < 0) return true;
            long now = clock.getAsLong();
            long openForMs = now - openedAt;
            if (openForMs < openWindowMs) return false;
            if (probeInFlight) {
                if (now - probeStartedAt < PROBE_ABANDON_MS) return false;
                log.warn("[UPSTREAM-BREAKER] 上一个半开探针超过 {}ms 未回传结果，视为放弃并放行新探针",
                        PROBE_ABANDON_MS);
            }
            probeInFlight = true;
            probeStartedAt = now;
            log.info("[UPSTREAM-BREAKER] 半开：放行 1 个探针请求, consecutiveFailures={}, openForMs={}",
                    consecutiveFailures, openForMs);
            return true;
        }
    }

    /** 任一平台调用成功（含 200 空结果）：闭路并清零；开路期间的成功同样立即闭路。 */
    public void recordSuccess() {
        int previousFailures;
        boolean stateChanged;
        synchronized (lock) {
            previousFailures = consecutiveFailures;
            stateChanged = openedAt >= 0 || probeInFlight || previousFailures > 0;
            consecutiveFailures = 0;
            openedAt = -1L;
            probeInFlight = false;
            probeStartedAt = 0L;
        }
        if (stateChanged) {
            log.info("[UPSTREAM-BREAKER] 闭路：上游调用成功，连续失败清零（此前 failures={}）", previousFailures);
        }
    }

    /**
     * 上报一次调用尝试的失败。
     *
     * <p>只有 {@code transportFailure=true} 才计数；4xx/解析失败等业务侧问题只清理半开探针占位、不计数，
     * 避免把「网关活着但业务报错」误判成整体故障。
     *
     * @param transportFailure 是否为传输层失败（见类注释口径）
     */
    public void recordAttemptFailure(boolean transportFailure) {
        boolean probeFailed;
        boolean openedNow = false;
        int failures;
        synchronized (lock) {
            probeFailed = probeInFlight;
            probeInFlight = false;
            probeStartedAt = 0L;
            if (!transportFailure) return;
            consecutiveFailures++;
            failures = consecutiveFailures;
            long now = clock.getAsLong();
            if (probeFailed) {
                // 探针失败：重开并重置计时（窗口从本次失败重新计算）
                openedAt = now;
            } else if (openedAt < 0 && consecutiveFailures >= failureThreshold) {
                openedAt = now;
                openedNow = true;
            }
        }
        if (probeFailed) {
            log.info("[UPSTREAM-BREAKER] 探针失败，重新开路 {}ms, consecutiveFailures={}", openWindowMs, failures);
        } else if (openedNow) {
            log.info("[UPSTREAM-BREAKER] 连续 {} 次传输失败，开路 {}ms（期间跳过 5 平台远程检索）",
                    failures, openWindowMs);
        }
    }

    /** 传输层失败上报的便捷入口。 */
    public void recordTransportFailure() {
        recordAttemptFailure(true);
    }

    /** 当前是否处于开路（含半开）状态。 */
    public boolean isOpen() {
        synchronized (lock) {
            return openedAt >= 0;
        }
    }

    /** 开路剩余毫秒；闭路返回 0。 */
    public long remainingOpenMs() {
        synchronized (lock) {
            if (openedAt < 0) return 0L;
            return Math.max(0L, openWindowMs - (clock.getAsLong() - openedAt));
        }
    }

    /** 连续传输失败计数（测试/指标用）。 */
    public int consecutiveFailures() {
        synchronized (lock) {
            return consecutiveFailures;
        }
    }

    /**
     * 传输层失败判定（纯函数，异常原因链逐层下钻）。
     * 5xx 计入，4xx 不计入；其余运行时异常（解析失败、NPE 等）不计入。
     */
    public static boolean isTransportFailure(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof RestClientResponseException response) {
                return response.getStatusCode().is5xxServerError();
            }
            if (current instanceof ResourceAccessException) return true;
            if (current instanceof ConnectException) return true;
            if (current instanceof SocketTimeoutException) return true;
            if (current instanceof java.net.SocketException) return true;
            if (current instanceof UnknownHostException) return true;
            if (current instanceof NoRouteToHostException) return true;
            if (current instanceof InterruptedIOException) return true;
            if (current instanceof ClosedChannelException) return true;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return false;
    }
}
