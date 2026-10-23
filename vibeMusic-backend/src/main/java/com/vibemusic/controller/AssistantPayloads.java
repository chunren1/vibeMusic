package com.vibemusic.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 上游响应解析（从 AssistantController 抽出，行为不变）。
 *
 * <p>只处理 OpenAI 兼容的 {@code choices[0]} 结构：tool_calls 判定/提取、
 * assistant 消息回填、content 读取。无 I/O、无状态。</p>
 */
final class AssistantPayloads {

    private AssistantPayloads() {
    }

    @SuppressWarnings("unchecked")
    static boolean hasToolCalls(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        return message != null && message.get("tool_calls") != null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractToolCalls(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        return (List<Map<String, Object>>) message.get("tool_calls");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> extractAssistantMessage(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", message.getOrDefault("content", ""));
        msg.put("tool_calls", message.get("tool_calls"));
        return msg;
    }

    @SuppressWarnings("unchecked")
    static String extractContent(Map<String, Object> choice) {
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        if (message == null) return null;
        return (String) message.get("content");
    }
}
