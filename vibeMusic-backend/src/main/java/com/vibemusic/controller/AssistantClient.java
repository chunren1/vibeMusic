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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 上游调用层（从 AssistantController 抽出，行为不变）：同步 Function Calling 调用、
 * 一次性读超时派生、工具结果 → 歌曲卡片解析。
 *
 * <p>职责边界：只负责「把消息发给 LLM 并如实返回 choices[0]」与「解析工具结果」；
 * 预算编排、降级判定、SSE 推送与落库仍在 controllers 侧（构造器由控制器自己 new）。
 *
 * <p>{@link #callLLMWithTools} / {@link #callLLM} 失败仍抛 {@link RuntimeException}，由调用方降级，本类不吞异常。
 */
@Slf4j
public class AssistantClient {

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

    public AssistantClient(RestTemplate restTemplate,
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
    Map<String, Object> callLLMWithTools(List<Map<String, Object>> messages, AiCallBudget budget) {
        return post(messages, aiToolService.getToolDefinitions(), budget);
    }

    /**
     * 调用 LLM（**不**带 Function Calling 工具定义）。
     * <p>
     * 与 {@link #callLLMWithTools} 共用同一套请求体字段（model / max_tokens / temperature / thinking）、
     * 超时派生与异常语义，唯一区别是不塞 {@code tools} / {@code tool_choice}。
     *
     * @throws IllegalStateException 预算已耗尽（调用方应先判 {@code budget.canCall()}）
     */
    public Map<String, Object> callLLM(List<Map<String, Object>> messages, AiCallBudget budget) {
        return post(messages, null, budget);
    }

    /**
     * 组装请求体 → 发 HTTP → 解析 {@code choices[0]}。
     *
     * @param toolsOrNull 工具定义；{@code null} 表示本次调用不启用 Function Calling
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> post(List<Map<String, Object>> messages,
                                     List<Map<String, Object>> toolsOrNull,
                                     AiCallBudget budget) {
        Duration timeout = budget.timeoutForNextCall();
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", aiModel);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", TEMPERATURE);
        if (toolsOrNull != null) {
            requestBody.put("tools", toolsOrNull);
            requestBody.put("tool_choice", "auto");
        }
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

            logUsage(body);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) return Map.of();

            return choices.get(0);
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    /**
     * 打印一次调用的 token 用量（DeepSeek 兼容格式 {@code usage.{prompt,completion,total}_tokens}）。
     * <p>只读日志、不改行为：AI 推荐与助手共用本条链路，用来核对「提示词压缩」到底省了多少。
     */
    private void logUsage(Map<String, Object> body) {
        Object usage = body.get("usage");
        if (!(usage instanceof Map<?, ?> map)) return;
        log.info("[AI 用量] prompt={}, completion={}, total={}",
                map.get("prompt_tokens"), map.get("completion_tokens"), map.get("total_tokens"));
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

    /** 单次对话最多回传多少张歌曲卡片：多轮 search_songs 能堆到几十首，前端一屏放不下。 */
    private static final int MAX_SONG_CARDS = 12;

    /**
     * 把歌单类工具的结构化结果转成前端可直接渲染的「操作结果」。
     *
     * <p>为什么必须由后端给出、而不是只让 LLM 说：模型会把工具返回的
     * `{"error":"未登录"}` 忽略掉，照样回一句「歌单建好了」。前端拿到这份
     * 结构化结果就能自己判断成功与否，不必信任模型的说法。
     *
     * <p>只收「写」类工具（create_playlist / add_to_playlist）；搜索类不产生副作用，
     * 不需要结果卡。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractActions(List<Map<String, Object>> toolResults) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> tr : toolResults) {
            String tool = String.valueOf(tr.get("name"));
            if (!"create_playlist".equals(tool) && !"add_to_playlist".equals(tool)) continue;
            try {
                Map<String, Object> r = objectMapper.readValue((String) tr.get("result"), Map.class);
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("tool", tool);
                Object error = r.get("error");
                if (error != null) {
                    a.put("ok", false);
                    a.put("error", String.valueOf(error));
                } else {
                    a.put("ok", true);
                    a.put("playlistId", r.get("playlistId"));
                    a.put("playlistName", r.containsKey("name") ? r.get("name") : r.get("playlistName"));
                    a.put("added", r.getOrDefault("added", 0));
                    Object failed = r.get("failed");
                    if (failed instanceof List<?> list && !list.isEmpty()) {
                        a.put("failedCount", list.size());
                    }
                }
                actions.add(a);
            } catch (Exception e) {
                log.warn("解析歌单工具结果失败: {}", e.getMessage());
            }
        }
        return actions;
    }

    /**
     * 从工具调用结果中提取歌曲列表。
     *
     * <p>按 {@code platform:sourceId} 去重：LLM 一轮对话里可能调两次 search_songs，
     * 同一首歌会被两条工具结果同时命中，不去重就会在消息里出现两张一模一样的卡片。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractSongsFromToolResults(List<Map<String, Object>> toolResults) {
        List<Map<String, Object>> songs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> tr : toolResults) {
            if (!"search_songs".equals(tr.get("name")) || songs.size() >= MAX_SONG_CARDS) continue;
            try {
                Map<String, Object> result = objectMapper.readValue((String) tr.get("result"), Map.class);
                List<Map<String, Object>> songList = (List<Map<String, Object>>) result.get("songs");
                if (songList == null) continue;
                for (Map<String, Object> s : songList) {
                    if (songs.size() >= MAX_SONG_CARDS) break;
                    String key = s.get("platform") + ":" + s.get("sourceId");
                    if (!seen.add(key)) continue;
                    Map<String, Object> card = new LinkedHashMap<>();
                    card.put("sourceId", s.get("sourceId"));
                    card.put("name", s.get("name"));
                    card.put("artist", s.getOrDefault("artist", ""));
                    card.put("platform", s.getOrDefault("platform", ""));
                    // 透传封面等展示字段：缺 coverUrl 时 App 只能渲染默认封面
                    card.put("coverUrl", s.getOrDefault("coverUrl", ""));
                    card.put("album", s.getOrDefault("album", ""));
                    card.put("duration", s.getOrDefault("duration", 0));
                    songs.add(card);
                }
            } catch (Exception e) {
                log.warn("解析歌曲工具结果失败: {}", e.getMessage());
            }
        }
        return songs;
    }
}
