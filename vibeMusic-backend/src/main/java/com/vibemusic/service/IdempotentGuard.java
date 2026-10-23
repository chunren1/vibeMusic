package com.vibemusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 幂等性守卫 — X-Request-Id 防重放
 *
 * 网络重试/快速连点可能导致同一请求被处理多次。
 * 用 Redis 缓存最近 5 分钟内的 Request-Id，相同 ID 直接返回幂等成功。
 *
 * 用法：
 *   if (!idempotentGuard.tryAcquire(requestId)) return "幂等跳过";
 *   ... 业务逻辑 ...
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentGuard {

    private final StringRedisTemplate stringRedisTemplate;
    private static final String PREFIX = "idempotent:";
    private static final Duration WINDOW = Duration.ofMinutes(5);
    /** 降级告警最小间隔：Redis 长时间不可用时避免每个请求刷一行 WARN */
    private static final long WARN_INTERVAL_MS = 60_000L;
    /** 上次降级告警时间戳（仅用于限流打印，不参与业务判定） */
    private final AtomicLong lastWarnAt = new AtomicLong(0L);

    /**
     * 尝试获取请求锁。
     * @return true=首次请求，正常处理；false=重复请求，应返回幂等成功
     */
    public boolean tryAcquire(String requestId) {
        if (requestId == null || requestId.isBlank()) return true; // 无 Request-Id，放行
        String key = PREFIX + requestId;
        Boolean set;
        try {
            set = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", WINDOW);
        } catch (RuntimeException e) {
            // fail-open：幂等是「防重复」的增强，不能反过来让收藏等核心写操作 5xx
            warnThrottled(key, e);
            return true;
        }
        // null：Redis 返回异常值，降级放行；true：首次请求；false：重复请求
        if (set == null || Boolean.TRUE.equals(set)) {
            return true;
        }
        log.info("[IDEMPOTENT] 重复请求已拦截: {}", requestId);
        return false;
    }

    /** Redis 故障告警（按 WARN_INTERVAL_MS 限流，避免刷屏）。 */
    private void warnThrottled(String key, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("[IDEMPOTENT] Redis 不可用，幂等降级放行（{}ms 内不重复告警）key={} err={}",
                    WARN_INTERVAL_MS, key, e.toString());
        }
    }
}
