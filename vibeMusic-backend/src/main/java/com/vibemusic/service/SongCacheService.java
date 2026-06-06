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

@Slf4j
@Service
@RequiredArgsConstructor
public class SongCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String SEARCH_PREFIX = "song:search:v2:";
    private static final Duration TTL_RESULTS = Duration.ofHours(1);
    private static final Duration TTL_EMPTY = Duration.ofMinutes(5);

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

    public List<SongDTO> getSearchCache(String keyword, int page) {
        try {
            String json = stringRedisTemplate.opsForValue().get(SEARCH_PREFIX + keyword);
            if (json == null) return Collections.emptyList();
            if (json.isEmpty() || "\"__EMPTY__\"".equals(json)) {
                return Collections.emptyList();
            }
            log.debug("Redis 命中搜索: {} page={}", keyword, page);
            return objectMapper.readValue(json, new TypeReference<List<SongDTO>>() {});
        } catch (Exception e) {
            log.warn("读取 Redis 缓存失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void setSearchCache(String keyword, int page, List<SongDTO> songs, boolean hasResults) {
        try {
            if (hasResults) {
                String json = objectMapper.writeValueAsString(songs);
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, json, TTL_RESULTS);
                log.info("Redis 缓存搜索 '{}': {} 首, TTL={}", keyword, songs.size(), TTL_RESULTS);
            } else {
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, "\"__EMPTY__\"", TTL_EMPTY);
                log.info("Redis 缓存空搜索 '{}': TTL={}", keyword, TTL_EMPTY);
            }
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败: {}", e.getMessage());
        }
    }

    public List<SongDTO> getSearchCache(String keyword) {
        return getSearchCache(keyword, 1);
    }
}
