package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 按账号的登录失败锁定（2026-11-06）。
 *
 * <p>背景：nginx 的 {@code limit_req zone=auth}（10r/m）只按出口 IP 计数，攻击者换 IP
 * 就能继续撞同一个账号。这里把计数键从「IP」换成「账号」，分布式撞库也能挡住。</p>
 *
 * <ul>
 *   <li>同一 username 连续失败 {@value #MAX_FAILURES} 次即锁定 {@value #LOCK_MINUTES} 分钟；</li>
 *   <li>key 用 {@code sha256(username)} 十六进制，用户名是用户输入，不能直接拼进 Redis 键
 *       （存量用户可能有中文/特殊字符用户名，故只做哈希、不限制字符集）；</li>
 *   <li>登录成功立即清空计数，避免历史失败把下一次正常登录拖进锁定。</li>
 * </ul>
 *
 * <p>fail-open：与 {@link RateLimitService} 同一口径——Redis 抖动时放行（只 WARN），
 * 不能因为缓存不可用把所有人的登录全部打死；此时仍有 nginx 按 IP 限流兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptGuard {

    /** 允许的连续失败次数；达到即锁定。 */
    static final int MAX_FAILURES = 5;

    /** 锁定时长（也是首次失败计数的 TTL）。 */
    static final long LOCK_MINUTES = 15;

    private static final Duration LOCK_DURATION = Duration.ofMinutes(LOCK_MINUTES);

    private static final String KEY_PREFIX = "user:auth:login:fail:";

    /**
     * 原子 INCR + 首次设 TTL：非原子写法（INCR 成功后进程挂掉）会留下永不过期的键，
     * 等于把该账号永久锁死，故与 {@link RateLimitService} 一样用 Lua 保证原子性。
     */
    private static final String INCR_WITH_TTL_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 是否已被锁定；已锁定直接抛 429（消息含剩余分钟数）。
     *
     * @param username 已 trim 的用户名（null/空白视为参数非法，交由调用方校验，不计入失败）
     * @throws BusinessException 429
     */
    public void checkLocked(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = key(username);
        Long count;
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            count = raw == null ? null : Long.valueOf(raw);
        } catch (NumberFormatException e) {
            // 键值被别的写入方污染：当作无计数，避免登录接口 500
            log.warn("[LoginGuard] 计数键内容非法，按未锁定处理 key={}", key);
            return;
        } catch (RuntimeException e) {
            log.warn("[LoginGuard] Redis 不可用，锁定检查降级放行 key={} err={}", key, e.toString());
            return;
        }
        if (count == null || count < MAX_FAILURES) {
            return;
        }
        long remainingSeconds;
        try {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            remainingSeconds = (ttl == null || ttl <= 0) ? LOCK_DURATION.getSeconds() : ttl;
        } catch (RuntimeException e) {
            log.warn("[LoginGuard] 读取锁定 TTL 失败，回落默认窗口 key={} err={}", key, e.toString());
            remainingSeconds = LOCK_DURATION.getSeconds();
        }
        long minutes = Math.max(1, (remainingSeconds + 59) / 60);
        throw new BusinessException(429,
                "该账号登录失败次数过多，已锁定，约 " + minutes + " 分钟后重试");
    }

    /**
     * 记一次失败（仅在 {@code BadCredentialsException} 时调用）。
     * <p>Redis 异常只 WARN，不影响原 401 语义。</p>
     */
    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = key(username);
        try {
            stringRedisTemplate.execute(
                    new DefaultRedisScript<>(INCR_WITH_TTL_SCRIPT, Long.class),
                    List.of(key),
                    String.valueOf(LOCK_DURATION.getSeconds()));
        } catch (RuntimeException e) {
            log.warn("[LoginGuard] Redis 不可用，失败计数未记录 key={} err={}", key, e.toString());
        }
    }

    /**
     * 登录成功后清空该账号的失败计数。
     * <p>Redis 异常只 WARN：清不掉最多让历史失败在 15min 内继续有效，不能因此让登录失败。</p>
     */
    public void clear(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = key(username);
        try {
            stringRedisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("[LoginGuard] Redis 不可用，失败计数未清除 key={} err={}", key, e.toString());
        }
    }

    /** Redis 键：{@code user:auth:login:fail:<sha256-hex>}。 */
    public static String key(String username) {
        return KEY_PREFIX + sha256(username);
    }

    /** sha256 十六进制（小写）。SHA-256 是 JDK 必备算法，理论上不会缺失，仍保留兜底避免 500。 */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            log.warn("[LoginGuard] SHA-256 不可用，降级 hashCode 摘要: {}", e.toString());
            return Integer.toHexString(value.hashCode());
        }
    }
}
