package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.PlaylistService;
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
    public Result<List<Map<String, Object>>> list(@RequestParam(defaultValue = "1") Long userId) {
        return Result.ok(playlistService.listPlaylists(userId));
    }

    /** 创建歌单 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.getOrDefault("userId", 1L)).longValue();
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        return Result.ok(playlistService.create(userId, name, description));
    }

    /** 添加歌曲到歌单 */
    @PostMapping("/add-song")
    public Result<Boolean> addSong(@RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.getOrDefault("userId", 1L)).longValue();
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
    public Result<Void> removeSong(@RequestParam(defaultValue = "1") Long userId,
                                   @RequestParam Long playlistId,
                                   @RequestParam String sourceId) {
        playlistService.removeSong(userId, playlistId, sourceId);
        return Result.ok();
    }

    /** 删除歌单 */
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam(defaultValue = "1") Long userId,
                               @RequestParam Long playlistId) {
        playlistService.delete(userId, playlistId);
        return Result.ok();
    }
}
