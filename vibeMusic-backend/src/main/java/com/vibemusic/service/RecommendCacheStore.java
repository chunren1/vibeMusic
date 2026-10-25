package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 推荐缓存与「已推荐记忆」读写（从 RecommendService 抽出，行为不变）。
 *
 * <p>只负责 Redis 侧：结果缓存读写与污染校验、平台故障期判定、
 * 已推荐 sourceId 集合的读写与容量裁剪。Key 前缀 / TTL / 上限全部原样。
 * <p>Redis 异常一律降级（读失败返回 null / 空集，写失败只打日志），不向调用方抛。
 */
@Slf4j
final class RecommendCacheStore {

    static final String CACHE_PREFIX = "recommend:v3:"; // v3: 新算法
    static final Duration USER_CACHE_TTL = Duration.ofMinutes(30); // 30min，推荐更及时
    static final Duration GUEST_CACHE_TTL = Duration.ofMinutes(10);
    private static final String SEEN_KEY_PREFIX = "recommend:seen:v3:";
    private static final int SEEN_MAX = 200;
    private static final Duration SEEN_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SongSearchService songSearchService;

    RecommendCacheStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                        SongSearchService songSearchService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.songSearchService = songSearchService;
    }

    /**
     * 读 Redis 缓存 + 校验：疑似污染且平台健康时删除重算；
     * 平台故障/熔断期不删（stale-while-revalidate：直接 serving 旧缓存），避免 TTL 形同虚设。
     */
    RecommendResult readCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            RecommendResult result = objectMapper.readValue(json, RecommendResult.class);
            if (RecommendRules.isCachePoisoned(result)) {
                if (isPlatformOutage()) {
                    log.warn("推荐缓存疑似污染但处于平台故障期，降级 serving 旧缓存: {}", key);
                    return result;
                }
                log.warn("推荐缓存已污染，自动清除: {} ({}首全来自{})",
                        key, result.getSongs().size(),
                        result.getSongs().get(0).getPlatform());
                stringRedisTemplate.delete(key);
                return null; // 触发重算
            }
            return result;
        } catch (Exception e) {
            log.warn("读取推荐缓存失败: {}", key, e);
            return null;
        }
    }

    /**
     * 写 Redis 缓存
     */
    void writeCache(String key, RecommendResult result, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(key, json, ttl);
            log.debug("推荐缓存写入: {}, TTL={}", key, ttl);
        } catch (Exception e) {
            log.warn("写入推荐缓存失败: {}", key, e);
        }
    }

    /**
     * 平台故障期判定：复用搜索链路已有的 QQ 熔断器状态，不另起健康体系。
     * QQ 开路期间单平台结果是预期现象而非污染，此时删缓存只会放大上游。
     */
    boolean isPlatformOutage() {
        try {
            return songSearchService.shouldSkipQq();
        } catch (Exception e) {
            log.debug("读取 QQ 熔断状态失败，按非故障期处理", e);
            return false;
        }
    }

    String seenKeyFor(Long userId, String deviceId) {
        if (userId != null) {
            return SEEN_KEY_PREFIX + "user:" + userId;
        }
        return SEEN_KEY_PREFIX + "guest:" + AnonymousIdentityUtils.sanitize(deviceId);
    }

    Set<String> readSeenIds(String key) {
        if (key == null) {
            return Collections.emptySet();
        }
        try {
            SetOperations<String, String> ops = stringRedisTemplate.opsForSet();
            if (ops == null) {
                return Collections.emptySet();
            }
            Set<String> members = ops.members(key);
            return members != null ? members : Collections.emptySet();
        } catch (Exception e) {
            log.debug("读取已推荐记忆失败，降级为空集: {}", key, e);
            return Collections.emptySet();
        }
    }

    void writeSeenIds(String key, List<SongDTO> songs) {
        if (key == null || songs == null || songs.isEmpty()) {
            return;
        }
        try {
            SetOperations<String, String> ops = stringRedisTemplate.opsForSet();
            if (ops == null) {
                return;
            }
            String[] ids = songs.stream()
                    .map(SongDTO::getSourceId)
                    .filter(Objects::nonNull)
                    .toArray(String[]::new);
            if (ids.length == 0) {
                return;
            }
            ops.add(key, ids);
            stringRedisTemplate.expire(key, SEEN_TTL);
            Long size = ops.size(key);
            if (size != null && size > SEEN_MAX) {
                long overflow = size - SEEN_MAX;
                for (long i = 0; i < overflow; i++) {
                    if (ops.pop(key) == null) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("写回已推荐记忆失败: {}", key, e);
        }
    }
}
