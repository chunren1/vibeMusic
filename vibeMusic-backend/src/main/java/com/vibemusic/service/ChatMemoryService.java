package com.vibemusic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.AnonymousIdentityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 对话记忆服务
 * <p>
 * 基于 Redis 存储会话历史，支持多轮上下文关联对话。
 * 每个用户独立会话，保留最近 MAX_HISTORY 轮（每轮 = user + assistant 各 1 条）。
 * <p>
 * Redis Key: chat:session:{userId}（已登录）；chat:session:anon:{deviceId}（匿名，按设备隔离）
 * TTL: 30 分钟（无活跃对话自动清除）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "chat:session:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final int MAX_MESSAGES = 20; // 保留最近 20 条消息（约 10 轮对话）

    /**
     * 构造会话 Key：已登录用户按 userId 独占；匿名用户按清洗后的设备标识隔离，
     * 避免陌生人共享同一份 LLM 上下文。
     */
    private static String buildKey(Long userId, String anonymousId) {
        if (userId != null) {
            return PREFIX + userId;
        }
        return PREFIX + "anon:" + AnonymousIdentityUtils.sanitize(anonymousId);
    }

    /**
     * 获取对话历史（匿名用户按设备标识隔离）
     * @param userId 用户 ID，null 表示匿名
     * @param anonymousId 匿名设备标识（已登录时忽略）
     * @return 消息列表（role + content），可能为空
     */
    public List<Map<String, String>> getHistory(Long userId, String anonymousId) {
        String key = buildKey(userId, anonymousId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) return new ArrayList<>();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("读取对话历史失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 追加一条消息到会话历史（匿名用户按设备标识隔离）
     */
    public void appendMessage(Long userId, String role, String content, String anonymousId) {
        String key = buildKey(userId, anonymousId);
        try {
            List<Map<String, String>> history = getHistory(userId, anonymousId);
            history.add(Map.of("role", role, "content", content));

            // 裁剪：保留最近 MAX_MESSAGES 条
            if (history.size() > MAX_MESSAGES) {
                history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
            }

            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history), SESSION_TTL);
        } catch (Exception e) {
            log.warn("保存对话历史失败: {}", e.getMessage());
        }
    }

    /**
     * 清除会话历史（匿名用户按设备标识隔离，只清自己的）
     */
    public void clearHistory(Long userId, String anonymousId) {
        String key = buildKey(userId, anonymousId);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("清除对话历史失败: {}", e.getMessage());
        }
    }
}
