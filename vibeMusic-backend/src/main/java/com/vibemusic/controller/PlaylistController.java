package com.vibemusic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.Result;
import com.vibemusic.service.NeteaseApiService;
import com.vibemusic.service.PlaylistService;
import com.vibemusic.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;
    private final NeteaseApiService neteaseApiService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PLAYLIST_CACHE_PREFIX = "playlist:v2:";
    private static final String RECOMMEND_CACHE_KEY = "playlist:recommend:v2";
    private static final Duration PLAYLIST_TTL = Duration.ofHours(6);
    private static final Duration RECOMMEND_TTL = Duration.ofHours(3);

    /** 歌单详情（网易云/QQ，Redis 缓存 6h） */
    @GetMapping("/detail")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> detail(@RequestParam String source, @RequestParam String id) {
        // 1. 查缓存
        String cacheKey = PLAYLIST_CACHE_PREFIX + source + ":" + id;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) return Result.ok(objectMapper.readValue(cached, Map.class));
        } catch (Exception ignored) {}

        Map<String, Object> raw;
        if ("qq".equals(source)) {
            raw = neteaseApiService.getQQPlaylist(id);
        } else {
            raw = neteaseApiService.getNeteasePlaylist(id);
        }
        if (raw == null) return Result.error(404, "歌单不存在");
        // 网易云通用代理返回 raw body: { code, playlist: {...} }
        Map<String, Object> pl = (Map<String, Object>) raw.get("playlist");
        if (pl == null) {
            // QQ 或其他格式兼容
            Map<String, Object> data = (Map<String, Object>) raw.get("data");
            if (data == null) return Result.error(404, "歌单不存在");
            return Result.ok(data);
        }
        // 转换为统一格式
        Map<String, Object> result = new HashMap<>();
        result.put("id", String.valueOf(pl.get("id")));
        result.put("name", pl.get("name"));
        result.put("description", pl.getOrDefault("description", ""));
        result.put("coverUrl", String.valueOf(pl.getOrDefault("coverImgUrl", "")).replace("http://", "https://"));
        Map<String, Object> creator = (Map<String, Object>) pl.get("creator");
        result.put("creator", Map.of("name", creator != null ? creator.getOrDefault("nickname", "") : "",
                "avatar", creator != null ? String.valueOf(creator.getOrDefault("avatarUrl", "")).replace("http://", "https://") : ""));
        result.put("playCount", pl.getOrDefault("playCount", 0));
        result.put("songCount", pl.getOrDefault("trackCount", 0));
        result.put("source", source);
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) pl.get("tracks");
        List<Map<String, Object>> songs = new ArrayList<>();
        if (tracks != null) {
            for (Map<String, Object> t : tracks) {
                Map<String, Object> s = new HashMap<>();
                s.put("id", String.valueOf(t.get("id")));
                s.put("name", t.getOrDefault("name", ""));
                List<Map<String, Object>> ar = (List<Map<String, Object>>) t.get("ar");
                s.put("artist", ar != null ? ar.stream().map(a -> String.valueOf(a.get("name"))).collect(Collectors.joining("/")) : "");
                Map<String, Object> al = (Map<String, Object>) t.get("al");
                s.put("album", al != null ? al.get("name") : "");
                s.put("coverUrl", al != null ? String.valueOf(al.get("picUrl")).replace("http://", "https://") : "");
                Object dt = t.get("dt");
                s.put("duration", dt instanceof Number ? ((Number) dt).intValue() / 1000 : 0);
                songs.add(s);
            }
        }
        result.put("songs", songs);

        // 2. 写缓存
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), PLAYLIST_TTL);
        } catch (Exception ignored) {}

        return Result.ok(result);
    }

    /** 网易云推荐歌单（首页"推荐歌单"区域，原始 30 个缓存 3h，每次随机取 6） */
    @GetMapping("/recommend")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> recommend() {
        try {
            // 1. 查缓存（缓存原始 30 个歌单数据，每次随机取 6）
            List<Map<String, Object>> allPlaylists;
            try {
                String cached = stringRedisTemplate.opsForValue().get(RECOMMEND_CACHE_KEY);
                if (cached != null) {
                    allPlaylists = objectMapper.readValue(cached, List.class);
                } else {
                    Map<String, Object> result = neteaseApiService.personalizedPlaylists(30);
                    if (result == null) return Result.ok(List.of());
                    List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("result");
                    if (list == null || list.isEmpty()) return Result.ok(List.of());
                    // 精简字段后缓存
                    allPlaylists = list.stream().map(p -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", p.get("id"));
                        m.put("name", String.valueOf(p.getOrDefault("name", "")));
                        m.put("picUrl", String.valueOf(p.getOrDefault("picUrl", "")).replace("http://", "https://"));
                        m.put("copywriter", String.valueOf(p.getOrDefault("copywriter", "精选歌单")));
                        m.put("playCount", p.getOrDefault("playCount", 0));
                        return m;
                    }).collect(Collectors.toList());
                    stringRedisTemplate.opsForValue().set(RECOMMEND_CACHE_KEY,
                            objectMapper.writeValueAsString(allPlaylists), RECOMMEND_TTL);
                }
            } catch (Exception e) {
                log.warn("推荐歌单缓存处理失败，回退到 API", e);
                Map<String, Object> result = neteaseApiService.personalizedPlaylists(30);
                if (result == null) return Result.ok(List.of());
                allPlaylists = ((List<Map<String, Object>>) result.get("result"))
                        .stream().map(p -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", p.get("id"));
                            m.put("name", String.valueOf(p.getOrDefault("name", "")));
                            m.put("picUrl", String.valueOf(p.getOrDefault("picUrl", "")));
                            m.put("copywriter", String.valueOf(p.getOrDefault("copywriter", "精选歌单")));
                            m.put("playCount", p.getOrDefault("playCount", 0));
                            return m;
                        }).collect(Collectors.toList());
            }

            if (allPlaylists.isEmpty()) return Result.ok(List.of());

            // 2. 随机打乱取 6 个
            List<Map<String, Object>> shuffled = new ArrayList<>(allPlaylists);
            Collections.shuffle(shuffled);
            List<Map<String, Object>> playlists = shuffled.stream().limit(6).map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.get("id"));
                m.put("name", String.valueOf(p.getOrDefault("name", "")));
                m.put("coverUrl", String.valueOf(p.getOrDefault("picUrl", "")).replace("http://", "https://"));
                m.put("desc", String.valueOf(p.getOrDefault("copywriter", "精选歌单")));
                m.put("count", p.getOrDefault("playCount", 0));
                m.put("source", "netease");
                return m;
            }).collect(Collectors.toList());
            return Result.ok(playlists);
        } catch (Exception e) {
            return Result.ok(List.of());
        }
    }

    /** 我的歌单列表 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.ok(playlistService.listPlaylists(userId));
    }

    /** 创建歌单 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        String coverUrl = (String) body.getOrDefault("coverUrl", "");
        // Bug3修复: name 必填校验
        if (name == null || name.trim().isEmpty()) return Result.error("歌单名称不能为空");
        return Result.ok(playlistService.create(userId, name.trim(), description, coverUrl));
    }

    /** 添加歌曲到歌单 */
    @PostMapping("/add-song")
    public Result<Boolean> addSong(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        Long playlistId = body.get("playlistId") instanceof Number n ? n.longValue() : null;
        String sourceId = (String) body.get("sourceId");
        String songName = (String) body.get("songName");
        if (playlistId == null) return Result.error("缺少 playlistId");
        if (sourceId == null || sourceId.isEmpty()) return Result.error("缺少 sourceId");
        if (songName == null || songName.isEmpty()) return Result.error("缺少 songName");
        String artist = (String) body.getOrDefault("artist", "");
        String coverUrl = (String) body.getOrDefault("coverUrl", "");
        Integer duration = body.get("duration") != null
                ? ((Number) body.get("duration")).intValue() : 0;
        return Result.ok(playlistService.addSong(userId, playlistId, sourceId, songName, artist, coverUrl, duration));
    }

    /** 获取歌单歌曲 */
    @GetMapping("/songs")
    public Result<List<Map<String, Object>>> songs(@RequestParam Long playlistId) {
        return Result.ok(playlistService.getSongs(playlistId));
    }

    /** 从歌单移除歌曲 */
    @DeleteMapping("/remove-song")
    public Result<Void> removeSong(@RequestParam Long playlistId,
                                   @RequestParam String sourceId) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        playlistService.removeSong(userId, playlistId, sourceId);
        return Result.ok();
    }

    /** 删除歌单 */
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam Long playlistId) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        playlistService.delete(userId, playlistId);
        return Result.ok();
    }

    /** 导入外部歌单到我的歌单（一键收藏） */
    @PostMapping("/import")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> importPlaylist(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        String source = (String) body.get("source");
        String id = (String) body.get("id");
        if (source == null || id == null) return Result.error("缺少 source 或 id 参数");
        log.info("导入歌单请求: userId={}, source={}, id={}", userId, source, id);

        // 1. 获取歌单详情
        Result<Map<String, Object>> detailResult = detail(source, id);
        if (detailResult.getCode() != 200 || detailResult.getData() == null) {
            return Result.error(404, "歌单不存在或获取失败");
        }
        Map<String, Object> playlistData = detailResult.getData();
        String name = (String) playlistData.get("name");
        String coverUrl = (String) playlistData.get("coverUrl");
        List<Map<String, Object>> songs = (List<Map<String, Object>>) playlistData.get("songs");
        log.info("导入歌单详情: name={}, songCount={}", name, songs != null ? songs.size() : 0);

        if (songs == null || songs.isEmpty()) return Result.error("歌单中没有歌曲");

        // 2. 导入到用户歌单
        int count = playlistService.importPlaylist(userId, name, coverUrl, songs);
        log.info("导入歌单完成: userId={}, name={}, imported={}/{}", userId, name, count, songs.size());

        Map<String, Object> result = new HashMap<>();
        result.put("imported", count);
        result.put("total", songs.size());
        result.put("name", name);
        return Result.ok(result);
    }
}
