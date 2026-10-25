package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI 歌单工具（Function Calling 三件套）：
 * <ul>
 *   <li>{@code list_playlists}：列出用户歌单（LLM 据此确认真实歌单名，别瞎编）</li>
 *   <li>{@code add_to_playlist}：按歌单名 + 歌名搜索后加入；歌单不存在直接报错，不静默建单</li>
 *   <li>{@code create_playlist}：建歌单 + 逐首搜索加入；单首失败不拖垮整体</li>
 * </ul>
 *
 * <p>从 {@link AiToolService} 拆出（单文件 ≤250 纯 LOC 约束）。schema 用静态方法暴露，
 * 让 {@code AiToolService} 只做注册与路由，无需持有实例即可列出工具。</p>
 *
 * <p>安全：不打印 Cookie / accesskey；日志只含歌单 id 与歌名。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlaylistTools {

    /** 单次调用最多处理的歌曲数：LLM 传 500 首会把上游搜索打爆。 */
    static final int MAX_SONGS_PER_CALL = 20;

    /** 每首歌取前几条结果，取第一条即可（再多只是浪费上游配额）。 */
    private static final int SEARCH_LIMIT = 3;

    private final PlaylistService playlistService;
    private final SongSearchService songSearchService;
    private final ObjectMapper objectMapper;

    /** 三个歌单工具的 schema（纯静态数据，无实例依赖）。 */
    public static List<Map<String, Object>> toolDefinitions() {
        return List.of(
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "list_playlists",
                    "description", "列出当前用户的歌单（歌单名 + 歌曲数）。当用户提到某个歌单名、或要把歌加到歌单时，先调用此工具确认真实歌单名，不要凭记忆编造歌单名。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", List.of()
                    )
                )
            ),
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "add_to_playlist",
                    "description", "把一首歌加入用户已存在的歌单。歌单必须已存在：找不到会返回 error（不会自动创建歌单），此时先用 list_playlists 查清歌单名。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "playlistName", Map.of("type", "string", "description", "目标歌单名，必须与用户已有歌单名一致"),
                            "songName", Map.of("type", "string", "description", "歌曲名"),
                            "artist", Map.of("type", "string", "description", "歌手名，可选，用于提高搜索准确度"),
                            "platform", Map.of("type", "string", "description", "来源平台，可选：netease/qq/migu/kugou/bilibili；缺省搜全部平台")
                        ),
                        "required", List.of("playlistName", "songName")
                    )
                )
            ),
            Map.of(
                "type", "function",
                "function", Map.of(
                    "name", "create_playlist",
                    "description", "创建新歌单（同名歌单已存在时复用），可附带一批歌曲。每首歌会先搜索再加入；搜不到的歌会列在 failed 里，不影响其他歌曲，请如实告诉用户哪几首没找到。",
                    "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "name", Map.of("type", "string", "description", "歌单名"),
                            "description", Map.of("type", "string", "description", "歌单描述，可选"),
                            "songs", Map.of(
                                "type", "array",
                                "description", "歌曲列表，可选。每项为「歌名」或「歌名 歌手」；单次最多 " + MAX_SONGS_PER_CALL + " 首，超出部分忽略",
                                "items", Map.of("type", "string"),
                                "maxItems", MAX_SONGS_PER_CALL
                            )
                        ),
                        "required", List.of("name")
                    )
                )
            )
        );
    }

    /** 该函数名是否属于本类的歌单工具。 */
    public static boolean supports(String functionName) {
        return "list_playlists".equals(functionName)
                || "add_to_playlist".equals(functionName)
                || "create_playlist".equals(functionName);
    }

    /**
     * 执行歌单工具。
     *
     * @param functionName 必须是 {@link #supports(String)} 为真的名字
     * @param args         已解析的参数
     * @return JSON 字符串（成功结果或 {"error": "..."}）
     */
    public String execute(String functionName, Map<String, Object> args) throws Exception {
        return switch (functionName) {
            case "list_playlists" -> listPlaylists();
            case "add_to_playlist" -> addToPlaylist(args);
            case "create_playlist" -> createPlaylist(args);
            default -> AiToolJson.error(objectMapper, "未知工具: " + functionName);
        };
    }

    // ---------- list_playlists ----------

    private String listPlaylists() throws Exception {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) {
            return AiToolJson.error(objectMapper, "未登录，无法查询歌单");
        }

        List<Map<String, Object>> playlists = new ArrayList<>();
        for (Map<String, Object> row : playlistService.listPlaylists(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("playlistId", row.get("id"));
            m.put("name", Objects.toString(row.get("name"), ""));
            m.put("songCount", row.get("songCount") instanceof Number n ? n.longValue() : 0L);
            playlists.add(m);
        }

        return objectMapper.writeValueAsString(Map.of("playlists", playlists, "total", playlists.size()));
    }

    // ---------- add_to_playlist ----------

    private String addToPlaylist(Map<String, Object> args) throws Exception {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) {
            return AiToolJson.error(objectMapper, "未登录，无法添加歌曲到歌单");
        }

        String playlistName = text(args.get("playlistName"));
        String songName = text(args.get("songName"));
        if (playlistName.isEmpty()) {
            return AiToolJson.error(objectMapper, "缺少参数 playlistName");
        }
        if (songName.isEmpty()) {
            return AiToolJson.error(objectMapper, "缺少参数 songName");
        }
        String artist = text(args.get("artist"));
        String platform = text(args.get("platform"));

        // 只按用户真实存在的歌单操作：找不到就报错，绝不静默创建（否则用户会莫名多出歌单）
        Map<String, Object> playlist = findPlaylist(userId, playlistName);
        if (playlist == null) {
            return AiToolJson.error(objectMapper,
                    "没有名为 " + playlistName + " 的歌单，可先用 list_playlists 查询");
        }
        Long playlistId = asLong(playlist.get("id"));

        String keyword = artist.isEmpty() ? songName : songName + " " + artist;
        SongDTO song = searchFirst(keyword, platform);
        if (song == null) {
            return AiToolJson.error(objectMapper, "没有找到歌曲: " + keyword);
        }

        boolean added = playlistService.addSong(userId, playlistId, song.getSourceId(),
                song.getName(), song.getArtist(), song.getCoverUrl(), song.getDuration(), song.getPlatform());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playlistId", playlistId);
        out.put("playlistName", Objects.toString(playlist.get("name"), playlistName));
        out.put("added", added);
        out.put("message", added ? "已添加到歌单" : "歌曲已在歌单中，未重复添加");
        out.put("song", songBrief(song));
        return objectMapper.writeValueAsString(out);
    }

    // ---------- create_playlist ----------

    private String createPlaylist(Map<String, Object> args) throws Exception {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) {
            return AiToolJson.error(objectMapper, "未登录，无法创建歌单");
        }

        String name = text(args.get("name"));
        if (name.isEmpty()) {
            return AiToolJson.error(objectMapper, "缺少参数 name");
        }
        String description = text(args.get("description"));
        List<String> requested = songItems(args.get("songs"));

        Map<String, Object> created = playlistService.create(userId, name,
                description.isEmpty() ? null : description, null);
        Long playlistId = asLong(created.get("id"));
        String playlistName = Objects.toString(created.get("name"), name);

        int added = 0;
        List<String> failed = new ArrayList<>();
        for (String item : requested) {
            // 单首失败（搜不到 / 加歌异常）只记入 failed，绝不让整次调用失败
            try {
                SongDTO song = searchFirst(item, "");
                if (song == null) {
                    failed.add(item);
                    continue;
                }
                if (playlistService.addSong(userId, playlistId, song.getSourceId(), song.getName(),
                        song.getArtist(), song.getCoverUrl(), song.getDuration(), song.getPlatform())) {
                    added++;
                }
            } catch (RuntimeException e) {
                log.warn("创建歌单时单曲处理失败: playlistId={}, item={}, error={}",
                        playlistId, item, e.getMessage());
                failed.add(item);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playlistId", playlistId);
        out.put("name", playlistName);
        out.put("added", added);
        out.put("failed", failed);
        return objectMapper.writeValueAsString(out);
    }

    // ---------- helpers ----------

    /** 按名字找用户歌单：先精确匹配，再忽略大小写匹配；找不到返回 null。 */
    private Map<String, Object> findPlaylist(Long userId, String name) {
        String target = name.trim();
        Map<String, Object> ignoreCase = null;
        for (Map<String, Object> row : playlistService.listPlaylists(userId)) {
            String rowName = Objects.toString(row.get("name"), "");
            if (rowName.equals(target)) {
                return row;
            }
            if (ignoreCase == null && rowName.equalsIgnoreCase(target)) {
                ignoreCase = row;
            }
        }
        return ignoreCase;
    }

    /** 搜索并取第一条有效结果（无 sourceId 的脏数据视为未找到）。 */
    private SongDTO searchFirst(String keyword, String platform) {
        SearchResult result = (platform == null || platform.isBlank())
                ? songSearchService.search(keyword, 1, SEARCH_LIMIT)
                : songSearchService.search(keyword, 1, SEARCH_LIMIT, platform);
        List<SongDTO> list = result == null ? null : result.getList();
        if (list == null || list.isEmpty()) {
            return null;
        }
        SongDTO first = list.get(0);
        return (first == null || first.getSourceId() == null || first.getSourceId().isBlank()) ? null : first;
    }

    /** 喂给 LLM 的歌曲摘要：字段顺序与 search_songs 一致（name/artist/sourceId/platform）。 */
    private static Map<String, Object> songBrief(SongDTO song) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", song.getName());
        m.put("artist", song.getArtist() != null ? song.getArtist() : "");
        m.put("sourceId", song.getSourceId());
        m.put("platform", song.getPlatform() != null ? song.getPlatform() : "");
        return m;
    }

    /** 解析 songs 参数：只接受数组里的字符串/标量，最多 {@link #MAX_SONGS_PER_CALL} 首。 */
    private static List<String> songItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Object o : list) {
            String s = text(o);
            if (!s.isEmpty()) {
                items.add(s);
            }
            if (items.size() >= MAX_SONGS_PER_CALL) {
                break;
            }
        }
        return items;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
