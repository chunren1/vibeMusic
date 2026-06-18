package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.NeteaseApiService;
import com.vibemusic.service.PlaylistService;
import com.vibemusic.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;
    private final NeteaseApiService neteaseApiService;

    /** 歌单详情（网易云/QQ） */
    @GetMapping("/detail")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> detail(@RequestParam String source, @RequestParam String id) {
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
        result.put("coverUrl", pl.getOrDefault("coverImgUrl", ""));
        Map<String, Object> creator = (Map<String, Object>) pl.get("creator");
        result.put("creator", Map.of("name", creator != null ? creator.getOrDefault("nickname", "") : "",
                "avatar", creator != null ? creator.getOrDefault("avatarUrl", "") : ""));
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
                s.put("coverUrl", al != null ? al.get("picUrl") : "");
                Object dt = t.get("dt");
                s.put("duration", dt instanceof Number ? ((Number) dt).intValue() / 1000 : 0);
                songs.add(s);
            }
        }
        result.put("songs", songs);
        return Result.ok(result);
    }

    /** 网易云推荐歌单（首页"推荐歌单"区域） */
    @GetMapping("/recommend")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> recommend() {
        try {
            // 一次性获取 30 个歌单，每次随机抽取 6 个，实现"换一批"效果
            Map<String, Object> result = neteaseApiService.personalizedPlaylists(30);
            if (result == null) return Result.ok(List.of());
            List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("result");
            if (list == null || list.isEmpty()) return Result.ok(List.of());

            // 随机打乱取前 6 个
            List<Map<String, Object>> shuffled = new ArrayList<>(list);
            Collections.shuffle(shuffled);
            List<Map<String, Object>> selected = shuffled.stream().limit(6).collect(Collectors.toList());

            List<Map<String, Object>> playlists = selected.stream().map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.get("id"));
                m.put("name", String.valueOf(p.getOrDefault("name", "")));
                m.put("coverUrl", String.valueOf(p.getOrDefault("picUrl", "")));
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
}
