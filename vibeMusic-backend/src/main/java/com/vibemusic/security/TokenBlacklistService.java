package com.vibemusic.security;

import com.vibemusic.common.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 黑名单（登出吊销）统一实现 —— 本地表 + Redis 双层。
 * <p>
 * 2026-10-19 收敛 {@link JwtAuthenticationFilter} 与 AuthController 的两份孪生实现，
 * 并修掉「Redis 写失败 → 登出静默失效」「Redis 抖动 → 已登出 token 仍可用」两个缺陷：
 * <ul>
 *   <li><b>写路径</b>{@link #revoke(String)}：先写本地表（不走网络，几乎不可能失败），再尽力写 Redis；</li>
 *   <li><b>读路径</b>{@link #isRevoked(String)}：本地命中（未过期）立即判定已吊销 —— fail-closed，
 *       Redis 挂掉也不放行已登出 token；本地未命中才查 Redis，此时 Redis 异常属“未知状态”，
 *       默认 fail-open（不阻塞正常请求），可由 {@code auth.blacklist.redis-degraded=fail-closed} 改为拒绝。</li>
 * </ul>
 * TTL 严格等于 token 剩余寿命（access 15min / refresh 7d），绝不截断 —— 截断会给 refresh token
 * 留出「黑名单已过期但 token 仍有效」的洞。
 * <p>
 * 本地表上限 {@value #DEFAULT_MAX_ENTRIES}（防内存无界增长）；超限先清扫过期项，仍超限则淘汰
 * 最早过期项并累加 {@link #getEvictedCount()} 计数器 + WARN 日志（<b>淘汰会牺牲该条 token 的安全性，
 * 必须可观测</b>）。Redis 读写降级各有一枚计数器。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";
    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtils jwtUtils;

    /** true = Redis 未知状态下拒绝（未来多实例部署用），默认 fail-open 不阻塞正常请求 */
    private final boolean failClosedWhenRedisDown;
    /** 可注入时钟（测试禁止 sleep） */
    private final Clock clock;
    private final int maxEntries;

    /** 本地吊销表：key=token 摘要（16 hex），value=吊销截止 epoch ms */
    private final Map<String, Long> revokedUntil = new ConcurrentHashMap<>();

    private final AtomicLong degradedReadCount = new AtomicLong();
    private final AtomicLong degradedWriteCount = new AtomicLong();
    private final AtomicLong evictedCount = new AtomicLong();

    @Autowired
    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate,
                                JwtUtils jwtUtils,
                                @Value("${auth.blacklist.redis-degraded:fail-open}") String redisDegraded) {
        this(stringRedisTemplate, jwtUtils, redisDegraded, Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    /** 测试专用：注入可控时钟与容量上限 */
    TokenBlacklistService(StringRedisTemplate stringRedisTemplate,
                          JwtUtils jwtUtils,
                          String redisDegraded,
                          Clock clock,
                          int maxEntries) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtUtils = jwtUtils;
        this.failClosedWhenRedisDown = "fail-closed".equalsIgnoreCase(trim(redisDegraded));
        this.clock = clock;
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    // ==================== 写路径 ====================

    /**
     * 吊销 token：先落本地表（失败概率极低），再尽力同步 Redis（跨实例/重启后仍生效）。
     * TTL = token 剩余寿命；已过期（剩余 ≤ 0）或不可解析的 token 无需吊销。
     */
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;

        long remainMs;
        try {
            remainMs = jwtUtils.getExpirationFromToken(token) - clock.millis();
        } catch (Exception e) {
            log.warn("token 黑名单：解析过期时间失败，跳过吊销: {}", e.getMessage());
            return;
        }
        if (remainMs <= 0) return; // 已过期，无需吊销

        String key = blacklistKey(token);
        revokedUntil.put(key, clock.millis() + remainMs);
        evictIfOverCapacity(key);

        try {
            stringRedisTemplate.opsForValue().set(key, "1", Duration.ofMillis(remainMs));
        } catch (Exception e) {
            long count = degradedWriteCount.incrementAndGet();
            log.error("token 黑名单写 Redis 失败（本地表已生效，跨实例吊销延迟；累计降级 {} 次）: key={}, err={}",
                    count, key, e.getMessage());
        }
    }

    // ==================== 读路径 ====================

    /**
     * token 是否已被吊销。
     * <p>
     * 本地命中（未过期）→ true（fail-closed：Redis 挂掉也认已登出 token）；
     * 本地未命中 → 查 Redis，Redis 异常时按 {@code auth.blacklist.redis-degraded} 决定（默认放行未知状态）。
     */
    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) return false;

        String key = blacklistKey(token);
        Long until = revokedUntil.get(key);
        if (until != null) {
            if (until > clock.millis()) return true;
            revokedUntil.remove(key, until); // 本地项已过期，清理后回落 Redis 判定
        }

        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            long count = degradedReadCount.incrementAndGet();
            log.error("token 黑名单读 Redis 失败，按 {} 降级（累计降级 {} 次）: {}",
                    failClosedWhenRedisDown ? "fail-closed（拒绝）" : "fail-open（放行未知状态）",
                    count, e.getMessage());
            return failClosedWhenRedisDown;
        }
    }

    // ==================== 容量控制 & 观测 ====================

    /** 超限：先清扫过期项，仍超限则淘汰最早过期项（保护本次写入项），并累加淘汰计数 */
    private void evictIfOverCapacity(String protectKey) {
        if (revokedUntil.size() <= maxEntries) return;

        long now = clock.millis();
        revokedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (revokedUntil.size() <= maxEntries) return;

        List<Map.Entry<String, Long>> candidates = new ArrayList<>(revokedUntil.entrySet());
        candidates.removeIf(entry -> entry.getKey().equals(protectKey));
        candidates.sort(Comparator.comparingLong(Map.Entry::getValue));

        int excess = revokedUntil.size() - maxEntries;
        for (int i = 0; i < excess && i < candidates.size(); i++) {
            Map.Entry<String, Long> victim = candidates.get(i);
            if (!revokedUntil.remove(victim.getKey(), victim.getValue())) continue;
            long count = evictedCount.incrementAndGet();
            log.warn("本地吊销表超上限 {}，淘汰最早过期项（该 token 吊销安全性下降；累计淘汰 {}）: key={}",
                    maxEntries, count, victim.getKey());
        }
    }

    /** SHA-256 摘要前 16 hex（64 bit）防碰撞，与旧实现一致；SHA-256 不可用时降级 hashCode */
    private static String blacklistKey(String token) {
        return TOKEN_BLACKLIST_PREFIX + hashToken(token);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 不可用，token 摘要降级为 hashCode");
            return Integer.toHexString(token.hashCode());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 本地表当前条目数（观测/测试用） */
    int localEntryCount() {
        return revokedUntil.size();
    }

    /** Redis 读降级次数（观测用） */
    public long getDegradedReadCount() {
        return degradedReadCount.get();
    }

    /** Redis 写降级次数（观测用） */
    public long getDegradedWriteCount() {
        return degradedWriteCount.get();
    }

    /** 容量淘汰次数（观测用，>0 表示已有 token 的吊销安全性下降） */
    public long getEvictedCount() {
        return evictedCount.get();
    }
}
