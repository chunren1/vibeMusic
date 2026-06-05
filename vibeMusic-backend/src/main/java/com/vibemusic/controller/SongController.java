package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.NeteaseApiService;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
@Tag(name = "歌曲", description = "搜索、播放、历史记录")
public class SongController {

    private final SongService songService;
    private final PlayHistoryService playHistoryService;
    private final NeteaseApiService neteaseApiService;

    /** Banner 轮播（网易云推荐歌单） */
    @GetMapping("/banner")
    @Operation(summary = "首页轮播图")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> banner() {
        try {
            Map<String, Object> result = neteaseApiService.personalizedPlaylists(5);
            List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("result");
            if (list == null) return Result.ok(List.of());

            List<Map<String, Object>> banners = list.stream().map(p -> {
                Map<String, Object> b = new HashMap<>();
                b.put("name", String.valueOf(p.getOrDefault("name", "")));
                b.put("coverUrl", String.valueOf(p.getOrDefault("picUrl", "")));
                b.put("desc", String.valueOf(p.getOrDefault("copywriter", "精选歌单")));
                b.put("playCount", p.getOrDefault("playCount", 0));
                return b;
            }).collect(Collectors.toList());
            return Result.ok(banners);
        } catch (Exception e) {
            return Result.ok(List.of());
        }
    }

    /** 搜索 */
    @GetMapping("/search")
    @Operation(summary = "搜索歌曲（Redis缓存1h + 多平台聚合）")
    public Result<List<SongDTO>> search(@RequestParam String keyword) {
        return Result.ok(songService.search(keyword));
    }

    /** 随机推荐 */
    @GetMapping("/random")
    @Operation(summary = "随机推荐歌曲")
    public Result<List<SongDTO>> randomSongs(@RequestParam(defaultValue = "8") int count) {
        return Result.ok(songService.getRandomSongs(count));
    }

    /** 播放（获取 URL + 记录历史） */
    @GetMapping("/play")
    @Operation(summary = "获取播放链接并记录历史")
    public Result<Map<String, Object>> play(
            @RequestParam String sourceId,
            @RequestParam String name,
            @RequestParam(defaultValue = "未知歌手") String artist,
            @RequestParam(required = false, defaultValue = "") String coverUrl) {

        // 1. 获取播放链接
        String playUrl = songService.getPlayUrl(sourceId);
        if (playUrl == null) {
            return Result.error("无法获取播放链接");
        }

        // 2. 记录播放历史（仅登录用户）
        Long userId = UserService.getCurrentUserId();
        if (userId != null) {
            playHistoryService.record(userId, sourceId, name, artist, coverUrl);
        }

        // 3. 返回
        Map<String, Object> data = new HashMap<>();
        data.put("url", playUrl);
        data.put("sourceId", sourceId);
        data.put("name", name);
        return Result.ok(data);
    }

    /** 最近播放 */
    @GetMapping("/history")
    @Operation(summary = "最近播放列表")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "20") int count) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.ok(List.of());
        return Result.ok(playHistoryService.recent(userId, count));
    }

    /** 获取歌词 */
    @GetMapping("/lyric")
    @Operation(summary = "获取歌曲歌词")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> lyric(@RequestParam String sourceId) {
        try {
            Map<String, Object> result = neteaseApiService.getLyric(sourceId);
            if (result == null) return Result.ok(List.of());

            Map<String, Object> lrc = (Map<String, Object>) result.get("lrc");
            if (lrc == null) return Result.ok(List.of());

            String lyricStr = (String) lrc.get("lyric");
            if (lyricStr == null || lyricStr.isEmpty()) return Result.ok(List.of());

            // 解析 LRC 格式 → [{time, text}]
            List<Map<String, Object>> lines = new ArrayList<>();
            String[] parts = lyricStr.split("\\n");
            for (String line : parts) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 匹配 [mm:ss.xx] 或 [mm:ss]
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d+))?\\](.*)")
                        .matcher(line);
                if (m.find()) {
                    int min = Integer.parseInt(m.group(1));
                    int sec = Integer.parseInt(m.group(2));
                    int ms = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                    String text = m.group(4).trim();
                    double time = min * 60 + sec + ms / 1000.0;
                    Map<String, Object> item = new HashMap<>();
                    item.put("time", time);
                    item.put("text", text.isEmpty() ? "♪" : text);
                    lines.add(item);
                }
            }
            return Result.ok(lines);
        } catch (Exception e) {
            log.error("获取歌词失败: {}", e.getMessage());
            return Result.ok(List.of());
        }
    }
}
