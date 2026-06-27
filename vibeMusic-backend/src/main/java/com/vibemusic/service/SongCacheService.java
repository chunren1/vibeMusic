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

@Slf4j
@Service
@RequiredArgsConstructor
public class SongCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // 版本号递增即可自然淘汰旧缓存，无需 KEYS 扫描
    private static final String SEARCH_PREFIX = "song:search:v4:";
    private static final Duration TTL_RESULTS = Duration.ofHours(2);
    private static final Duration TTL_PARTIAL = Duration.ofSeconds(30); // 某平台空结果仅存30秒，快速重试
    private static final Duration TTL_EMPTY = Duration.ofSeconds(10); // 空结果仅缓存10秒，快速恢复

    @PostConstruct
    public void init() {
        log.info("[CACHE-LAYER] Redis 搜索缓存就绪, 前缀={}", SEARCH_PREFIX);
    }

    public List<SongDTO> getSearchCache(String keyword, int page) {
        try {
            String json = stringRedisTemplate.opsForValue().get(SEARCH_PREFIX + keyword);
            if (json == null) return Collections.emptyList();
            if (json.isEmpty() || "\"__EMPTY__\"".equals(json)) {
                return Collections.emptyList();
            }
            log.debug("[CACHE-LAYER] Redis 命中搜索: {} page={}", keyword, page);
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
            return Collections.emptyList();
        }
    }

    public void setSearchCache(String keyword, int page, List<SongDTO> songs, boolean hasResults) {
        setSearchCache(keyword, page, songs, hasResults, false);
    }

    /** @param incomplete 某平台返回空结果 → 用短TTL让恢复后快速生效 */
    public void setSearchCache(String keyword, int page, List<SongDTO> songs, boolean hasResults, boolean incomplete) {
        try {
            if (hasResults) {
                Duration ttl = incomplete ? TTL_PARTIAL : TTL_RESULTS;
                String json = objectMapper.writeValueAsString(songs);
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, json, ttl);
                log.info("[CACHE-LAYER] 写入成功: '{}', {}首, TTL={}{}",
                        keyword, songs.size(), ttl, incomplete ? " [不完整]" : "");
            } else {
                stringRedisTemplate.opsForValue().set(SEARCH_PREFIX + keyword, "\"__EMPTY__\"", TTL_EMPTY);
                log.info("[CACHE-LAYER] 写入空结果: '{}', TTL={}", keyword, TTL_EMPTY);
            }
        } catch (Exception e) {
            log.warn("[CACHE-LAYER] 写入失败: {}", e.getMessage());
        }
    }

    public List<SongDTO> getSearchCache(String keyword) {
        return getSearchCache(keyword, 1);
    }
}
