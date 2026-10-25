package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.config.RestTemplateConfig;
import com.vibemusic.service.AiCallBudget;
import com.vibemusic.service.AiToolService;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 上游调用层（从 AssistantController 抽出，行为不变）：同步 Function Calling 调用、
 * 一次性读超时派生、工具结果 → 歌曲卡片解析。
 *
 * <p>职责边界：只负责「把消息发给 LLM 并如实返回 choices[0]」与「解析工具结果」；
 * 预算编排、降级判定、SSE 推送与落库仍在 controllers 侧（构造器由控制器自己 new）。
 *
 * <p>{@link #callLLMWithTools} 失败仍抛 {@link RuntimeException}，由调用方降级，本类不吞异常。
 */
@Slf4j
class AssistantClient {

    /** 单次 LLM 回复上限（同步/流式共用，值不变）。 */
    static final int MAX_TOKENS = 800;
    /** 采样温度（同步/流式共用，值不变）。 */
    static final double TEMPERATURE = 0.7;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiToolService aiToolService;
    private final String apiUrl;
    private final String apiKey;
    private final String aiModel;
    private final String thinkingMode;

    AssistantClient(RestTemplate restTemplate,
                    ObjectMapper objectMapper,
                    AiToolService aiToolService,
                    String apiUrl,
                    String apiKey,
                    String aiModel,
                    String thinkingMode) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.aiToolService = aiToolService;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.aiModel = aiModel;
        this.thinkingMode = thinkingMode;
    }

    /**
     * 调用 LLM（带 Function Calling 工具定义）。
     * <p>
     * 本次调用的 socket 读超时 = {@code min(单次上限 60s, 剩余预算)}：
     * 通过请求级 {@code RequestConfig} 覆盖（共享同一个连接池的 HttpClient），
     * 不改全局 AI 客户端配置，也不影响其它调用方。
     *
     * @throws IllegalStateException 预算已耗尽（调用方应先判 {@code budget.canCall()}）
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> callLLMWithTools(List<Map<String, Object>> messages, AiCallBudget budget) {
        Duration timeout = budget.timeoutForNextCall();
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", TEMPERATURE);
        requestBody.put("tools", aiToolService.getToolDefinitions());
        requestBody.put("tool_choice", "auto");
        requestBody.put("thinking", Map.of("type", thinkingMode));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            if (timeout.toMillis() < 5_000L) {
                log.warn("[AI 预算] 剩余预算不足，收紧本次 LLM 超时: timeoutMs={}, remainingMs={}",
                        timeout.toMillis(), budget.remainingMillis());
            } else {
                log.debug("[AI 预算] LLM 调用: timeoutMs={}, remainingMs={}", timeout.toMillis(), budget.remainingMillis());
            }

            ResponseEntity<Map> response = withReadTimeout(restTemplate, timeout).postForEntity(
                    apiUrl, new HttpEntity<>(requestBody, headers), Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) return Map.of();

            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) return Map.of();

            return choices.get(0);
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    /**
     * 用「本次调用可用时长」派生一个一次性 RestTemplate：底层复用同一连接池的 HttpClient，
     * 只在请求级 {@code RequestConfig} 上覆盖响应超时（Spring 会把 readTimeout 合并进请求级配置）。
     * <p>
     * 拿不到底层 Apache 客户端时（如单测里的 mock）原样返回，行为与旧版一致。
     */
    static RestTemplate withReadTimeout(RestTemplate base, Duration timeout) {
        if (!(base.getRequestFactory() instanceof HttpComponentsClientHttpRequestFactory factory)) return base;
        if (!(factory.getHttpClient() instanceof CloseableHttpClient client)) return base;
        HttpComponentsClientHttpRequestFactory perCall = new HttpComponentsClientHttpRequestFactory(client);
        perCall.setReadTimeout(timeout);
        perCall.setConnectionRequestTimeout(
                AiCallBudget.shorter(RestTemplateConfig.CONNECTION_REQUEST_TIMEOUT, timeout));
        return new RestTemplate(perCall);
    }

    /**
     * 从工具调用结果中提取歌曲列表
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractSongsFromToolResults(List<Map<String, Object>> toolResults) {
        List<Map<String, Object>> songs = new ArrayList<>();
        for (Map<String, Object> tr : toolResults) {
            if ("search_songs".equals(tr.get("name"))) {
                try {
                    Map<String, Object> result = objectMapper.readValue((String) tr.get("result"), Map.class);
                    List<Map<String, Object>> songList = (List<Map<String, Object>>) result.get("songs");
                    if (songList != null) {
                        for (Map<String, Object> s : songList) {
                            Map<String, Object> card = new HashMap<>();
                            card.put("sourceId", s.get("sourceId"));
                            card.put("name", s.get("name"));
                            card.put("artist", s.getOrDefault("artist", ""));
                            card.put("platform", s.getOrDefault("platform", ""));
                            songs.add(card);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析歌曲工具结果失败: {}", e.getMessage());
                }
            }
        }
        return songs;
    }
}
