package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.utils.SongIdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 的「收藏」与「歌词」工具（2026-11-11）。
 *
 * <p>从 {@link AiToolService} 拆出的第二组非歌单工具：一个类只做两件与歌单无关的事，
 * 避免主工具类继续膨胀（体量守卫）。schema 由 {@link #toolDefinitions()} 注册，
 * 执行由 {@link #execute} 路由，与歌单三件套（{@link AiPlaylistTools}）同构。</p>
 *
 * <ul>
 *   <li>favorite_song：切换收藏（已收藏再调用会取消）——删除/破坏类操作不在此列，
 *       收藏是幂等可逆的双向切换，属于安全的写操作</li>
 *   <li>get_lyrics：取歌词纯文本（剥时间轴、截断 800 字防 token 爆炸）</li>
 * </ul>
 */
@Slf4j
@Service
public class AiActionTools {

    private final FavoriteService favoriteService;
    private final NeteaseApiService neteaseApiService;
    private final ObjectMapper objectMapper;

    public AiActionTools(FavoriteService favoriteService,
                         NeteaseApiService neteaseApiService,
                         ObjectMapper objectMapper) {
        this.favoriteService = favoriteService;
        this.neteaseApiService = neteaseApiService;
        this.objectMapper = objectMapper;
    }

    /** 工具 schema（注册顺序即呈现顺序）。 */
    public static List<Map<String, Object>> toolDefinitions() {
        return List.of(favoriteDefinition(), lyricsDefinition());
    }

    private static Map<String, Object> favoriteDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "favorite_song",
                        "description",
                        "收藏或取消收藏歌曲（切换语义：已收藏的再调用一次会取消）。"
                                + "当用户说「收藏这首」「加到我喜欢」「取消收藏」时调用。"
                                + "sourceId/name/artist 从搜索结果里取。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "sourceId", Map.of("type", "string", "description", "歌曲 ID（搜索结果里的 sourceId）"),
                                        "name", Map.of("type", "string", "description", "歌名"),
                                        "artist", Map.of("type", "string", "description", "歌手"),
                                        "coverUrl", Map.of("type", "string", "description", "封面地址，可为空")
                                ),
                                "required", List.of("sourceId")
                        )
                )
        );
    }

    private static Map<String, Object> lyricsDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "get_lyrics",
                        "description",
                        "获取歌曲的歌词文本。当用户问「这首歌歌词是什么」「这首歌讲什么」时调用。"
                                + "B站音源没有歌词；返回文本可能被截断。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "sourceId", Map.of("type", "string", "description", "歌曲 ID")
                                ),
                                "required", List.of("sourceId")
                        )
                )
        );
    }

    /** 路由执行；未知工具走统一错误信封。 */
    public String execute(String functionName, Map<String, Object> args) throws Exception {
        return switch (functionName) {
            case "favorite_song" -> executeFavorite(args);
            case "get_lyrics" -> executeGetLyrics(args);
            default -> AiToolJson.error(objectMapper, "未知工具: " + functionName);
        };
    }

    private String executeFavorite(Map<String, Object> args) throws Exception {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return AiToolJson.error(objectMapper, "未登录，无法收藏");
        String sourceId = str(args, "sourceId");
        if (sourceId.isBlank()) return AiToolJson.error(objectMapper, "缺少歌曲 ID");
        String name = str(args, "name");
        if (name.isBlank()) name = sourceId;
        boolean favorited = favoriteService.toggle(
                userId, sourceId, name, str(args, "artist"), str(args, "coverUrl"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("favorited", favorited);
        r.put("name", name);
        r.put("sourceId", sourceId);
        return objectMapper.writeValueAsString(r);
    }

    private String executeGetLyrics(Map<String, Object> args) throws Exception {
        String sourceId = str(args, "sourceId");
        if (sourceId.isBlank()) return AiToolJson.error(objectMapper, "缺少歌曲 ID");
        String platform = SongIdUtils.guessPlatform(sourceId);
        if ("bilibili".equals(platform)) {
            return AiToolJson.error(objectMapper, "B站音源没有歌词");
        }
        Map<String, Object> raw = switch (platform) {
            case "kugou" -> neteaseApiService.getKugouLyric(sourceId, null);
            case "netease" -> neteaseApiService.getLyric(sourceId);
            default -> neteaseApiService.getQQLyric(sourceId);
        };
        String wrapperKey = "netease".equals(platform) ? "lrc" : "data";
        String lyric = extractLyricText(raw, wrapperKey);
        if (lyric == null || lyric.isBlank()) {
            return AiToolJson.error(objectMapper, "这首歌暂时没有歌词");
        }
        String text = stripLrcTags(lyric);
        boolean truncated = text.length() > 800;
        if (truncated) text = text.substring(0, 800) + "…";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lyrics", text);
        r.put("truncated", truncated);
        return objectMapper.writeValueAsString(r);
    }

    @SuppressWarnings("unchecked")
    private static String extractLyricText(Map<String, Object> result, String wrapperKey) {
        if (result == null) return null;
        Object wrapper = result.get(wrapperKey);
        if (!(wrapper instanceof Map<?, ?>)) return null;
        Object lyric = ((Map<String, Object>) wrapper).get("lyric");
        return lyric == null ? null : String.valueOf(lyric);
    }

    /** 剥掉 [mm:ss.xx] 时间轴，按行保留正文；空行丢弃。 */
    static String stripLrcTags(String lrc) {
        StringBuilder sb = new StringBuilder();
        for (String line : lrc.split("\n")) {
            String text = line.replaceAll("\\[[^\\]]*\\]", "").trim();
            if (text.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
        }
        return sb.toString();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
