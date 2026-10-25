package com.vibemusic.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 助手对话入参（POST /api/assistant/chat 与 POST /api/assistant/stream 共用）。
 * <p>
 * 两个字段都可空，且各有默认值（message → "推荐一首歌给我"，context → ""），
 * 默认值逻辑留在原处（controller / AssistantStreamOrchestrator），此处不加约束。
 */
public record AssistantChatRequest(String message, String context) {

    /**
     * 转成 AssistantStreamOrchestrator#stream 读取的 body map（/stream 只换入参形状，不动编排器）。
     * <p>
     * 只写入非 null 字段：编排器用 {@code getOrDefault} 取默认值，显式写入 null 会绕过默认值。
     */
    public Map<String, Object> toBodyMap() {
        Map<String, Object> body = new HashMap<>(2);
        if (message != null) body.put("message", message);
        if (context != null) body.put("context", context);
        return body;
    }
}
