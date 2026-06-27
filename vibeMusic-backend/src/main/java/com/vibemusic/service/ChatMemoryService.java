package com.vibemusic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Redis Key: chat:session:{userId}
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
     * 获取用户的对话历史
     * @param userId 用户 ID，null 时使用 "anonymous"
     * @return 消息列表（role + content），可能为空
     */
    public List<Map<String, String>> getHistory(Long userId) {
        String key = PREFIX + (userId != null ? userId : "anonymous");
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
     * 追加一条消息到会话历史，并自动裁剪超长部分
     */
    public void appendMessage(Long userId, String role, String content) {
        String key = PREFIX + (userId != null ? userId : "anonymous");
        try {
            List<Map<String, String>> history = getHistory(userId);
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
     * 清除用户会话历史
     */
    public void clearHistory(Long userId) {
        String key = PREFIX + (userId != null ? userId : "anonymous");
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("清除对话历史失败: {}", e.getMessage());
        }
    }
}
