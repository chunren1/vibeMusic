package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 工具调用服务（Function Calling）
 * <p>
 * 定义 AI 可调用的工具函数 schema，并执行 LLM 返回的 tool_calls。
 * 让 AI 从"只会聊天"升级为"能操作音乐系统"的 Agent。
 * <p>
 * 当前支持的工具：
 * - search_songs: 搜索歌曲
 * - get_user_history: 获取用户播放历史
 * - create_playlist: 创建歌单（预留）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolService {

    private final SongSearchService songSearchService;
    private final PlayHistoryMapper playHistoryMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取所有工具的 schema 定义（OpenAI Function Calling 格式）
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return List.of(
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "search_songs",
                    "description", "搜索歌曲。当用户想听歌、找歌、推荐歌曲时调用此工具。返回歌曲列表（含歌名、歌手、封面）。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "keyword", Map.of("type", "string", "description", "搜索关键词，如歌手名、歌曲名、情绪关键词"),
                            "limit", Map.of("type", "integer", "description", "返回数量，默认6", "default", 6)
                        ),
                        "required", List.of("keyword")
                    )
                )
            ),
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "get_user_history",
                    "description", "获取用户最近播放历史。当用户问'我最近听了什么'、'根据我的口味推荐'时调用。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "limit", Map.of("type", "integer", "description", "返回数量，默认10", "default", 10)
                        ),
                        "required", List.of()
                    )
                )
            )
        );
    }

    /**
     * 执行 LLM 返回的工具调用
     * @param functionName 工具函数名
     * @param argumentsJson 参数 JSON 字符串
     * @return 工具执行结果（JSON 字符串，回传给 LLM）
     */
    @SuppressWarnings("unchecked")
    public String executeTool(String functionName, String argumentsJson) {
        try {
            Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                    ? objectMapper.readValue(argumentsJson, Map.class)
                    : Map.of();

            return switch (functionName) {
                case "search_songs" -> executeSearchSongs(args);
                case "get_user_history" -> executeGetUserHistory(args);
                default -> "{\"error\":\"未知工具: " + functionName + "\"}";
            };
        } catch (Exception e) {
            log.error("工具调用执行失败: function={}, error={}", functionName, e.getMessage());
            return "{\"error\":\"工具执行异常: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 执行歌曲搜索工具
     */
    private String executeSearchSongs(Map<String, Object> args) throws Exception {
        String keyword = (String) args.getOrDefault("keyword", "热门歌曲");
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 6;

        List<SongDTO> results = songSearchService.search(keyword, 1, limit).getList();
        List<Map<String, Object>> simplified = results.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("artist", s.getArtist() != null ? s.getArtist() : "");
            m.put("sourceId", s.getSourceId());
            m.put("platform", s.getPlatform() != null ? s.getPlatform() : "");
            return m;
        }).collect(Collectors.toList());

        return objectMapper.writeValueAsString(Map.of("songs", simplified, "total", simplified.size()));
    }

    /**
     * 执行获取播放历史工具
     */
    private String executeGetUserHistory(Map<String, Object> args) throws Exception {
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 10;
        Long userId = UserService.getCurrentUserId();

        if (userId == null) {
            return "{\"error\":\"未登录，无法获取播放历史\"}";
        }

        List<PlayHistory> history = playHistoryMapper.selectList(
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId)
                        .orderByDesc(PlayHistory::getPlayedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 50))));

        List<Map<String, Object>> simplified = history.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.getSongName());
            m.put("artist", h.getArtist() != null ? h.getArtist() : "");
            return m;
        }).collect(Collectors.toList());

        return objectMapper.writeValueAsString(Map.of("history", simplified, "total", simplified.size()));
    }
}
