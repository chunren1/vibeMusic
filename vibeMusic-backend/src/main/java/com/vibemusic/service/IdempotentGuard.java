package com.vibemusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

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

    /**
     * 尝试获取请求锁。
     * @return true=首次请求，正常处理；false=重复请求，应返回幂等成功
     */
    public boolean tryAcquire(String requestId) {
        if (requestId == null || requestId.isBlank()) return true; // 无 Request-Id，放行
        String key = PREFIX + requestId;
        Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", WINDOW);
        boolean first = Boolean.TRUE.equals(set);
        if (!first) {
            log.info("[IDEMPOTENT] 重复请求已拦截: {}", requestId);
        }
        return first;
    }
}
