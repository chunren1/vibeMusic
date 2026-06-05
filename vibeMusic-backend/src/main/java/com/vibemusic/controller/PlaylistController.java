package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.PlaylistService;
import com.vibemusic.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

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
        // Bug3修复: name 必填校验
        if (name == null || name.trim().isEmpty()) return Result.error("歌单名称不能为空");
        return Result.ok(playlistService.create(userId, name.trim(), description));
    }

    /** 添加歌曲到歌单 */
    @PostMapping("/add-song")
    public Result<Boolean> addSong(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        Long playlistId = ((Number) body.get("playlistId")).longValue();
        String sourceId = (String) body.get("sourceId");
        String songName = (String) body.get("songName");
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
