package com.vibemusic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SongDTO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 搜索缓存 —— 基于 Redis String（JSON）
 * <p>
 * Key:   song:search:{keyword}
 * Value: JSON Array of SongDTO
 * TTL:   1 小时
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SEARCH_PREFIX = "song:search:";
    private static final Duration TTL = Duration.ofHours(1);

    /**
     * 启动时清空旧缓存（防止脏数据残留）
     */
    @PostConstruct
    public void clearAllCache() {
        try {
            Set<String> keys = stringRedisTemplate.keys(SEARCH_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("启动清除 {} 个旧搜索缓存", keys.size());
            }
        } catch (Exception e) {
            log.warn("清除缓存失败（Redis 未启动？）: {}", e.getMessage());
        }
    }

    /**
     * 获取缓存的搜索结果
     */
    public List<SongDTO> getSearchCache(String keyword) {
        try {
            String json = stringRedisTemplate.opsForValue().get(SEARCH_PREFIX + keyword);
            if (json == null || json.isEmpty()) return Collections.emptyList();
            log.debug("Redis 命中搜索: {}", keyword);
            return objectMapper.readValue(json, new TypeReference<List<SongDTO>>() {});
        } catch (Exception e) {
            log.warn("读取 Redis 缓存失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 缓存搜索结果
     */
    public void setSearchCache(String keyword, List<SongDTO> songs) {
        if (songs == null || songs.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(songs);
            stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, json, TTL);
            log.info("Redis 缓存搜索结果 '{}': {} 首, TTL={}", keyword, songs.size(), TTL);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败: {}", e.getMessage());
        }
    }
}
