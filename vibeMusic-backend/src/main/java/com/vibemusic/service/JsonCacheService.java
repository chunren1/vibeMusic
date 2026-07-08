package com.vibemusic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 通用 JSON 缓存服务 — Controller 层 Redis 缓存逻辑统一入口
 * <p>
 * 替代直接注入 StringRedisTemplate + ObjectMapper 到 Controller 的做法
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 读取缓存并反序列化 */
    public <T> T get(String key, TypeReference<T> typeRef) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.debug("缓存读取失败: key={}", key);
            return null;
        }
    }

    /** 读取缓存为 Map */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAsMap(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("缓存读取失败: key={}", key);
            return null;
        }
    }

    /** 读取缓存为 List */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAsList(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.debug("缓存读取失败: key={}", key);
            return null;
        }
    }

    /** 写入缓存 */
    public void set(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.debug("缓存写入失败: key={}", key);
        }
    }

    /** 设置空缓存占位（防穿透） */
    public void setEmpty(String key, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, "\"__EMPTY__\"", ttl);
        } catch (Exception e) {
            log.debug("空缓存写入失败: key={}", key);
        }
    }

    /** 删除缓存 */
    public void delete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("缓存删除失败: key={}", key);
        }
    }

    /** 检查 key 是否存在 */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    /** 设置字符串值（非 JSON） */
    public void setString(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.debug("字符串缓存写入失败: key={}", key);
        }
    }
}
