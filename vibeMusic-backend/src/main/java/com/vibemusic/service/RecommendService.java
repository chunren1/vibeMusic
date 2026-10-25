package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 个性化推荐入口（v3：随机打底 + 兴趣扩展）。
 *
 * <p>本类只保留编排：缓存命中 → 个性化/游客 → 异常兜底 → 写缓存 → 返回。
 * 缓存与已推荐记忆见 {@link RecommendCacheStore}，游客/随机兜底与文案见
 * {@link RecommendGuestBuilder}，个性化算法见 {@link RecommendPersonalizer}。
 * <p>7 参 public 构造器与拆分前 {@code @RequiredArgsConstructor} 的签名/顺序一致（既有单测直接 new）。
 */
@Slf4j
@Service
public class RecommendService {

    /** 每批推荐歌曲数（个性化目标数与游客随机数共用同一口径）。 */
    static final int RECOMMEND_COUNT = 8;

    /** 删除用户缓存用（evictUserCache 直接落 Redis）。 */
    private final StringRedisTemplate stringRedisTemplate;
    private final RecommendCacheStore cacheStore;
    private final RecommendGuestBuilder guestBuilder;
    private final RecommendPersonalizer personalizer;

    public RecommendService(PlayHistoryMapper playHistoryMapper,
                            SongMapper songMapper,
                            SongSearchService songSearchService,
                            StorageService storageService,
                            StringRedisTemplate stringRedisTemplate,
                            ObjectMapper objectMapper,
                            ThreadPoolTaskExecutor searchExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheStore = new RecommendCacheStore(stringRedisTemplate, objectMapper, songSearchService);
        this.guestBuilder = new RecommendGuestBuilder(songSearchService, songMapper, storageService);
        this.personalizer = new RecommendPersonalizer(playHistoryMapper, songSearchService,
                searchExecutor, cacheStore, guestBuilder);
    }

    /**
     * 个性化推荐入口
     * @param userId   用户ID（null=未登录）
     * @param deviceId 设备标识（未登录时用于缓存隔离）
     * @param refresh  是否跳过缓存强制刷新（如用户点击"换一批"）
     */
    public RecommendResult getPersonalized(Long userId, String deviceId, boolean refresh) {
        String cacheKey = userId != null
                ? RecommendCacheStore.CACHE_PREFIX + "user:" + userId
                : RecommendCacheStore.CACHE_PREFIX + "guest:" + AnonymousIdentityUtils.sanitize(deviceId);

        // 非刷新模式：尝试读缓存
        if (!refresh) {
            RecommendResult cached = cacheStore.readCache(cacheKey);
            if (cached != null) {
                log.debug("推荐缓存命中: {}", cacheKey);
                return cached;
            }
        }

        // 执行推荐
        RecommendResult result;
        Duration ttl;
        // 已推荐记忆键：个性化路径使用（guest 路径保持原有行为，不使用 seen，避免扩散游客键）
        String seenKey = cacheStore.seenKeyFor(userId, deviceId);
        try {
            if (userId != null) {
                result = personalizer.buildPersonalized(userId, seenKey);
                ttl = RecommendCacheStore.USER_CACHE_TTL;
            } else {
                result = guestBuilder.buildGuest();
                ttl = RecommendCacheStore.GUEST_CACHE_TTL;
            }
        } catch (Exception e) {
            log.warn("推荐逻辑异常，降级到随机推荐", e);
            result = guestBuilder.buildGuest();
            ttl = RecommendCacheStore.GUEST_CACHE_TTL;
        }

        cacheStore.writeCache(cacheKey, result, ttl);
        return result;
    }

    /** 兼容旧调用（默认不走刷新） */
    public RecommendResult getPersonalized(Long userId, String deviceId) {
        return getPersonalized(userId, deviceId, false);
    }

    /**
     * 删除用户推荐缓存（播放新歌后触发）
     */
    public void evictUserCache(Long userId) {
        try {
            String key = RecommendCacheStore.CACHE_PREFIX + "user:" + userId;
            stringRedisTemplate.delete(key);
            log.debug("删除推荐缓存: {}", key);
        } catch (Exception e) {
            log.warn("删除推荐缓存失败", e);
        }
    }
}
