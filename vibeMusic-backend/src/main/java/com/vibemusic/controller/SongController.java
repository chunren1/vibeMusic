package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.SongService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
@Tag(name = "歌曲", description = "搜索、播放、历史记录")
public class SongController {

    private final SongService songService;
    private final PlayHistoryService playHistoryService;

    /** 搜索（Redis → 网易云API） */
    @GetMapping("/search")
    @Operation(summary = "搜索歌曲（Redis缓存1h + 网易云API）")
    public Result<List<SongDTO>> search(@RequestParam String keyword) {
        return Result.ok(songService.search(keyword));
    }

    /** 随机推荐 */
    @GetMapping("/random")
    @Operation(summary = "随机推荐歌曲")
    public Result<List<SongDTO>> randomSongs(@RequestParam(defaultValue = "8") int count) {
        return Result.ok(songService.getRandomSongs(count));
    }

    /**
     * 播放（获取 URL + 记录历史）
     * GET /api/songs/play?sourceId=xxx&name=xxx&artist=xxx
     */
    @GetMapping("/play")
    @Operation(summary = "获取播放链接并记录历史")
    public Result<Map<String, Object>> play(
            @RequestParam String sourceId,
            @RequestParam String name,
            @RequestParam(defaultValue = "未知歌手") String artist,
            @RequestParam(defaultValue = "0") Long userId) {

        // 1. 获取播放链接
        String playUrl = songService.getPlayUrl(sourceId);
        if (playUrl == null) {
            return Result.error("无法获取播放链接");
        }

        // 2. 记录播放历史
        if (userId > 0) {
            playHistoryService.record(userId, sourceId, name, artist);
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
    public Result<List<Map<String, Object>>> history(
            @RequestParam(defaultValue = "0") Long userId,
            @RequestParam(defaultValue = "20") int count) {
        if (userId <= 0) return Result.ok(List.of());
        return Result.ok(playHistoryService.recent(userId, count));
    }
}
