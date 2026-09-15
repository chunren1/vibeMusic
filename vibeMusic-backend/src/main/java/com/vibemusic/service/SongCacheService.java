package com.vibemusic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SongDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 歌曲搜索缓存服务
 * <p>
 * 提供分布式锁防止缓存击穿（单飞模式）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // 版本号递增即可自然淘汰旧缓存，无需 KEYS 扫描
    // v6: 合并搜索 payload 由 4 平台变为 5 平台(含 B 站)，旧 v5 :all 条目会遮蔽 B 站结果
    private static final String SEARCH_PREFIX = "song:search:v6:";
    private static final String LOCK_PREFIX = "song:search:lock:";
    // ES 已下线（二级缓存 Redis → API），结果 TTL 由 2h 延长至 6h 以覆盖原 ES 6h 清理窗口
    private static final Duration TTL_RESULTS = Duration.ofHours(6);
    private static final Duration TTL_PARTIAL = Duration.ofSeconds(30); // 某平台空结果仅存30秒，快速重试
    private static final Duration TTL_EMPTY = Duration.ofSeconds(10); // 空结果仅缓存10秒，快速恢复
    private static final Duration LOCK_TTL = Duration.ofSeconds(10); // 锁过期时间，防止死锁

    /** 负缓存哨兵（JSON 序列化形态）：key 缺失=null=未命中；读到哨兵=明确空结果，不再穿透。 */
    public static final String EMPTY_SENTINEL_JSON = "\"__EMPTY__\"";

    // Lua 脚本：原子释放锁（仅当锁值匹配时删除）
    private static final String UNLOCK_SCRIPT = """
            if redis.call("get", KEYS[1]) == ARGV[1] then
                return redis.call("del", KEYS[1])
            else
                return 0
            end
            """;

    @PostConstruct
    public void init() {
        log.info("[CACHE-LAYER] Redis 搜索缓存就绪, 前缀={}", SEARCH_PREFIX);
    }

    /**
     * 尝试获取分布式锁（SET NX + 过期时间）
     * @param keyword 缓存键关键词
     * @return 锁值（UUID），获取失败返回 null（Redis 不可用时也返回 null，走降级）
     */
    public String tryLock(String keyword) {
        String lockKey = LOCK_PREFIX + keyword;
        String lockValue = UUID.randomUUID().toString();
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("[CACHE-LOCK] 获取锁成功: {}", lockKey);
                return lockValue;
            }
            log.debug("[CACHE-LOCK] 获取锁失败（已被持有）: {}", lockKey);
            return null;
        } catch (Exception e) {
            log.debug("[CACHE-LOCK] 获取锁异常（Redis 不可用，降级为无锁）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 释放分布式锁（Lua 脚本原子操作，仅释放自己持有的锁）
     * @param keyword 缓存键关键词
     * @param lockValue tryLock 返回的锁值
     */
    public void releaseLock(String keyword, String lockValue) {
        if (lockValue == null) return;
        String lockKey = LOCK_PREFIX + keyword;
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
            Long result = stringRedisTemplate.execute(script, List.of(lockKey), lockValue);
            if (result != null && result == 1) {
                log.debug("[CACHE-LOCK] 释放锁成功: {}", lockKey);
            } else {
                log.debug("[CACHE-LOCK] 释放锁跳过（锁已过期或被他人持有）: {}", lockKey);
            }
        } catch (Exception e) {
            log.warn("[CACHE-LOCK] 释放锁异常: {}", e.getMessage());
        }
    }

    /**
     * 双语义读取：返回 null=缓存未命中（调用方继续穿透）；
     * 返回空 List=命中负缓存哨兵（调用方直接返回空，不再穿透）。
     * Redis 异常/损坏数据一律按未命中处理（fail-open），不污染为空结果。
     */
    public List<SongDTO> getSearchCache(String keyword) {
        try {
            String json = stringRedisTemplate.opsForValue().get(SEARCH_PREFIX + keyword);
            if (json == null) return null;
            if (EMPTY_SENTINEL_JSON.equals(json)) {
                log.debug("[CACHE-LAYER] 命中空结果哨兵: {}", keyword);
                return Collections.emptyList();
            }
            if (json.isEmpty()) return null;
            log.debug("[CACHE-LAYER] Redis 命中搜索: {}", keyword);
            List<SongDTO> songs = objectMapper.readValue(json, new TypeReference<List<SongDTO>>() {});
            // 单平台检测：仅告警，不清空缓存。
            // 原因：某些关键词（如"告白气球"）可能只在 QQ 有结果，清空缓存会导致每次请求都穿透 API 等 4 秒。
            // 有部分结果总比每次重新穿透 API 好。
            if (keyword.endsWith(":all") && songs.size() >= 4) {
                long netease = songs.stream().filter(s -> "netease".equals(s.getPlatform())).count();
                long qq = songs.stream().filter(s -> "qq".equals(s.getPlatform())).count();
                if (netease == 0 || qq == 0) {
                    log.warn("[CACHE-LAYER] 单平台缓存 ({}首全来自{}), 保留缓存避免穿透: {}",
                            songs.size(), netease == 0 ? "QQ" : "网易云", keyword);
                }
            }
            return songs;
        } catch (Exception e) {
            log.warn("[CACHE-LAYER] 读取 Redis 缓存失败: {}", e.getMessage());
            return null;
        }
    }

    public void setSearchCache(String keyword, List<SongDTO> songs, boolean hasResults) {
        setSearchCache(keyword, songs, hasResults, false);
    }

    /** @param incomplete 某平台返回空结果 → 用短TTL让恢复后快速生效 */
    public void setSearchCache(String keyword, List<SongDTO> songs, boolean hasResults, boolean incomplete) {
        try {
            if (hasResults) {
                Duration ttl = incomplete ? TTL_PARTIAL : TTL_RESULTS;
                String json = objectMapper.writeValueAsString(songs);
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, json, ttl);
                log.info("[CACHE-LAYER] 写入成功: '{}', {}首, TTL={}{}",
                        keyword, songs.size(), ttl, incomplete ? " [不完整]" : "");
            } else {
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, EMPTY_SENTINEL_JSON, TTL_EMPTY);
                log.info("[CACHE-LAYER] 写入空结果: '{}', TTL={}", keyword, TTL_EMPTY);
            }
        } catch (Exception e) {
            log.warn("[CACHE-LAYER] 写入失败: {}", e.getMessage());
        }
    }
}
