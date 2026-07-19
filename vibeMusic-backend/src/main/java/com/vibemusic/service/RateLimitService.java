package com.vibemusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

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
