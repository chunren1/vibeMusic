package com.vibemusic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * QQ 上游熔断状态机（从 SongSearchService 抽出，行为不变）。
 *
 * <p>进程内状态为主：连续失败/超时达 {@link #FAILURE_THRESHOLD} 开路，开路窗口
 * {@link #OPEN_MS} 过后进入半开，仅放行一个探针；探针成功闭路、失败重开并重置计时。
 *
 * <p>跨实例：开路状态与失败计数落在 Redis Hash（{@link #REDIS_KEY}）里，供重启后的
 * 新实例与多实例共享；Redis 任何异常都只降级为内存状态，绝不向调用方抛出——
 * 熔断器自身故障不能升级成搜索故障。
 */
@Slf4j
class QqCircuitBreaker {

    /** 连续失败/超时达此次数开路。 */
    static final int FAILURE_THRESHOLD = 3;

    /** 开路时长（也是 Redis 键 TTL）。 */
    static final long OPEN_MS = 5 * 60 * 1000L;

    /**
     * Redis 持久化键（Hash）：failures=连续失败计数，openedAt=开路时刻毫秒（-1=闭路）。
     * TTL 恒等于开路窗口；键过期即视为闭路（与内存语义一致：窗口过后进入半开探针）。
     * 半开探针占用标记仅保存在进程内，跨实例重复探针无害（恰好计数一次）。
     */
    static final String REDIS_KEY = "circuit:qq:v1";

    private static final String F_FAILURES = "failures";
    private static final String F_OPENED_AT = "openedAt";

    private final StringRedisTemplate stringRedisTemplate;

    // 状态变更统一在 lock 下；计数器用 AtomicInteger，时钟 volatile 可注入（测试用）。
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenedAt = -1L; // -1 = 闭路
    private volatile boolean halfOpenProbeInFlight = false;
    private final Object lock = new Object();
    private volatile LongSupplier clock = System::currentTimeMillis;

    QqCircuitBreaker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 测试用时钟注入（生产恒为 System::currentTimeMillis）。 */
    void setClockForTest(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * 开路期间是否应跳过 QQ 分支。半开时仅放行一个探针，其余并发请求继续跳过。
     * 开路时刻优先读 Redis（重启后新实例同样可见）；Redis 不可用或无状态时降级为进程内内存。
     */
    boolean shouldSkip() {
        long openedAt = readOpenedAt();
        if (openedAt < 0) return false;
        long now = clock.getAsLong();
        if (now - openedAt < OPEN_MS) return true;
        synchronized (lock) {
            openedAt = readOpenedAt();
            if (openedAt < 0) return false;
            now = clock.getAsLong();
            if (now - openedAt < OPEN_MS) return true;
            if (halfOpenProbeInFlight) return true;
            halfOpenProbeInFlight = true;
            return false;
        }
    }

    /** 成功：清零计数、闭路（探针成功同样闭路）。内存与 Redis 双写，Redis 异常只降级不抛。 */
    void recordSuccess() {
        consecutiveFailures.set(0);
        synchronized (lock) {
            circuitOpenedAt = -1L;
            halfOpenProbeInFlight = false;
        }
        try {
            if (stringRedisTemplate != null) stringRedisTemplate.delete(REDIS_KEY);
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 清除开路状态失败，降级为内存状态: {}", e.getMessage());
        }
    }

    /** 失败/超时：累计；达阈值开路，探针失败则重开并重置计时。计数经 Redis 原子累加实现跨实例共享。 */
    void recordFailure() {
        int n = incrementFailures();
        synchronized (lock) {
            halfOpenProbeInFlight = false;
            if (n >= FAILURE_THRESHOLD) {
                long now = clock.getAsLong();
                circuitOpenedAt = now;
                writeOpenState(n, now);
            }
        }
    }

    /** 开路时刻：Redis 优先（-1 亦视为有效闭路状态），缺失/异常时用内存值。 */
    private long readOpenedAt() {
        try {
            HashOperations<String, String, String> ops = hashOps();
            if (ops != null) {
                String v = ops.get(REDIS_KEY, F_OPENED_AT);
                if (v != null) return Long.parseLong(v);
            }
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 读取开路状态失败，降级为内存状态: {}", e.getMessage());
        }
        return circuitOpenedAt;
    }

    /** 失败计数：Redis HINCRBY 原子跨实例累加并同步内存镜像；Redis 不可用时内存自增。 */
    private int incrementFailures() {
        try {
            HashOperations<String, String, String> ops = hashOps();
            if (ops != null) {
                Long n = ops.increment(REDIS_KEY, F_FAILURES, 1L);
                if (n != null) {
                    int v = n.intValue();
                    consecutiveFailures.set(v);
                    refreshTtl();
                    return v;
                }
            }
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 累计失败次数失败，降级为内存计数: {}", e.getMessage());
        }
        return consecutiveFailures.incrementAndGet();
    }

    /** 开路写 Redis：failures + openedAt 双字段，TTL 恒等于开路窗口。异常只降级不抛。 */
    private void writeOpenState(int failures, long openedAt) {
        try {
            HashOperations<String, String, String> ops = hashOps();
            if (ops == null) return;
            ops.put(REDIS_KEY, F_FAILURES, String.valueOf(failures));
            ops.put(REDIS_KEY, F_OPENED_AT, String.valueOf(openedAt));
            refreshTtl();
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 写入开路状态失败，降级为内存状态: {}", e.getMessage());
        }
    }

    /** 刷新熔断器键 TTL = 开路窗口；键过期即视为闭路。异常只降级不抛。 */
    private void refreshTtl() {
        try {
            if (stringRedisTemplate != null)
                stringRedisTemplate.expire(REDIS_KEY, OPEN_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[QQ-BREAKER] Redis 刷新熔断器 TTL 失败，降级为内存状态: {}", e.getMessage());
        }
    }

    private HashOperations<String, String, String> hashOps() {
        try {
            if (stringRedisTemplate == null) return null;
            return stringRedisTemplate.opsForHash();
        } catch (Exception e) {
            return null;
        }
    }
}
