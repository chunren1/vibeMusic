package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.DownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
@Tag(name = "下载", description = "下载歌曲到 RustFS")
public class DownloadController {

    private final DownloadService downloadService;

    /**
     * 下载歌曲到 RustFS（同时存入 DB）
     * POST /api/download/{sourceId}
     * Body: { name, artist, album, coverUrl, duration, level }
     */
    @PostMapping("/{sourceId}")
    @Operation(summary = "下载歌曲到 RustFS")
    public Result<String> download(
            @PathVariable @Parameter(description = "网易云歌曲ID") String sourceId,
            @RequestBody @Parameter(description = "歌曲信息") Map<String, Object> params) {

        String name = (String) params.getOrDefault("name", sourceId);
        String artist = (String) params.getOrDefault("artist", "未知歌手");
        String album = (String) params.getOrDefault("album", "");
        String coverUrl = (String) params.getOrDefault("coverUrl", "");
        Integer duration = params.get("duration") instanceof Number n ? n.intValue() : 0;
        String level = (String) params.getOrDefault("level", "exhigh");

        String url = downloadService.download(sourceId, name, artist, album, coverUrl, duration, level);
        return Result.ok("下载成功", url);
    }

    /**
     * 检查歌曲是否已在 RustFS 缓存
     */
    @GetMapping("/check/{sourceId}")
    @Operation(summary = "检查歌曲是否已缓存")
    public Result<Boolean> check(@PathVariable String sourceId) {
        // TODO: 调用 StorageService.exists("songs/" + sourceId + ".mp3")
        return Result.ok(false);
    }
}
