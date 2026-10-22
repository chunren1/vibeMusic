package com.vibemusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 简易限流器（Redis INCR + TTL）
 * <p>防止 AI API Key 被刷导致余额耗尽</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String PREFIX = "ratelimit:";

    /**
     * @param key     限流键（如 "assistant:user:1"）
     * @param max     时间窗口内最大次数
     * @param window  时间窗口
     * @return true=放行, false=限流
     */
    // Lua 脚本: 原子 INCR + 首次时设 TTL（修复非原子操作的竞态条件）
    private static final String ATOMIC_INCR_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    /**
     * 剩余窗口秒数（用于 429 的 {@code Retry-After}）。
     * <p>TTL 已过期/键不存在/Redis 返回异常值时回落到调用方给的默认窗口——
     * 该值只影响提示精度，不影响正确性，故不额外打日志。</p>
     *
     * @return 严格大于 0 的秒数
     */
    public long remainingSeconds(String key, long fallback) {
        try {
            Long ttl = stringRedisTemplate.getExpire(PREFIX + key, TimeUnit.SECONDS);
            return (ttl == null || ttl <= 0) ? fallback : ttl;
        } catch (RuntimeException e) {
            log.warn("[RateLimit] 读取 TTL 失败，回落默认窗口 {}s: {}", fallback, e.toString());
            return fallback;
        }
    }

    public boolean tryAcquire(String key, int max, Duration window) {
        String redisKey = PREFIX + key;
        Long count = stringRedisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(ATOMIC_INCR_SCRIPT, Long.class),
                java.util.List.of(redisKey),
                String.valueOf(window.getSeconds())
        );
        if (count == null) return true;
        if (count > max) {
            log.warn("[RateLimit] {} 超出限制: {}/{} (窗口:{})", key, count - 1, max, window);
            return false;
        }
        return true;
    }
}
