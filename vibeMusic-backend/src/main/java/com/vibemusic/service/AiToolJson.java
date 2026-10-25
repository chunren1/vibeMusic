package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * AI 工具结果的 JSON 序列化单一出口。
 * <p>
 * 历史缺陷：错误信封用字符串拼接（{@code "{\"error\":\"" + msg + "\"}"}），
 * 而异常消息里常带引号/换行（Jackson 解析错误就是典型），拼出来不是合法 JSON，
 * LLM 侧解析直接失败。所有错误信封必须经此方法序列化，保证形状恒为 {@code {"error": "..."}}。
 */
final class AiToolJson {

    private AiToolJson() {
    }

    /**
     * 构造错误信封（JSON 字符串）。
     *
     * @param message 错误描述，允许含引号/换行/反斜杠；null 或空白回落通用文案
     */
    static String error(ObjectMapper objectMapper, String message) {
        String text = (message == null || message.isBlank()) ? "工具执行异常" : message;
        try {
            return objectMapper.writeValueAsString(Map.of("error", text));
        } catch (Exception e) {
            // String 序列化理论上不会失败；兜底也必须返回合法 JSON
            return "{\"error\":\"工具执行异常\"}";
        }
    }
}
