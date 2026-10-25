package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 工具调用服务（Function Calling）
 * <p>
 * 定义 AI 可调用的工具函数 schema，并执行 LLM 返回的 tool_calls。
 * 让 AI 从"只会聊天"升级为"能操作音乐系统"的 Agent。
 * <p>
 * 工具清单（schema 顺序即注册顺序，勿随意调整）：
 * <ul>
 *   <li>search_songs：搜索歌曲</li>
 *   <li>get_user_history：获取用户播放历史</li>
 *   <li>list_playlists / add_to_playlist / create_playlist：歌单三件套，实现见 {@link AiPlaylistTools}</li>
 * </ul>
 * 本类只做 schema 注册 + 路由，歌单相关执行逻辑已拆到 {@link AiPlaylistTools}（单文件 ≤250 纯 LOC）。
 */
@Slf4j
@Service
public class AiToolService {

    private final SongSearchService songSearchService;
    private final PlayHistoryMapper playHistoryMapper;
    private final ObjectMapper objectMapper;

    /** 歌单三件套执行器；Spring 装配必有，仅兼容旧三参构造的单测里为 null。 */
    private final AiPlaylistTools playlistTools;

    @Autowired
    public AiToolService(SongSearchService songSearchService,
                         PlayHistoryMapper playHistoryMapper,
                         ObjectMapper objectMapper,
                         AiPlaylistTools playlistTools) {
        this.songSearchService = songSearchService;
        this.playHistoryMapper = playHistoryMapper;
        this.objectMapper = objectMapper;
        this.playlistTools = playlistTools;
    }

    /**
     * 兼容既有单测的三参装配（无歌单执行器）。
     * 生产环境一律走 {@link #AiToolService(SongSearchService, PlayHistoryMapper, ObjectMapper, AiPlaylistTools)}。
     */
    AiToolService(SongSearchService songSearchService,
                  PlayHistoryMapper playHistoryMapper,
                  ObjectMapper objectMapper) {
        this(songSearchService, playHistoryMapper, objectMapper, null);
    }

    /**
     * 获取所有工具的 schema 定义（OpenAI Function Calling 格式）
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>(5);
        defs.add(searchSongsDefinition());
        defs.add(getUserHistoryDefinition());
        defs.addAll(AiPlaylistTools.toolDefinitions());
        return List.copyOf(defs);
    }

    private static Map<String, Object> searchSongsDefinition() {
        return Map.of(
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
        );
    }

    private static Map<String, Object> getUserHistoryDefinition() {
        return Map.of(
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
                case "list_playlists", "add_to_playlist", "create_playlist" -> executePlaylistTool(functionName, args);
                default -> AiToolJson.error(objectMapper, "未知工具: " + functionName);
            };
        } catch (Exception e) {
            log.error("工具调用执行失败: function={}, error={}", functionName, e.getMessage(), e);
            String detail = e.getMessage();
            return AiToolJson.error(objectMapper,
                    (detail == null || detail.isBlank()) ? "工具执行异常" : "工具执行异常: " + detail);
        }
    }

    /** 歌单工具路由（实现见 {@link AiPlaylistTools}）。 */
    private String executePlaylistTool(String functionName, Map<String, Object> args) throws Exception {
        if (playlistTools == null) {
            return AiToolJson.error(objectMapper, "歌单工具未装配");
        }
        return playlistTools.execute(functionName, args);
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
            return AiToolJson.error(objectMapper, "未登录，无法获取播放历史");
        }

        List<PlayHistory> history = playHistoryMapper.selectPage(
                new Page<>(1, Math.max(1, Math.min(limit, 50))),
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId)
                        .orderByDesc(PlayHistory::getPlayedAt)).getRecords();

        List<Map<String, Object>> simplified = history.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.getSongName());
            m.put("artist", h.getArtist() != null ? h.getArtist() : "");
            return m;
        }).collect(Collectors.toList());

        return objectMapper.writeValueAsString(Map.of("history", simplified, "total", simplified.size()));
    }
}
