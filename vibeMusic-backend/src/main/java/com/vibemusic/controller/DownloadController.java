package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.entity.Song;
import com.vibemusic.service.DownloadService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
@Tag(name = "下载", description = "下载歌曲到 RustFS 及浏览器下载")
public class DownloadController {

    private final DownloadService downloadService;
    private final StorageService storageService;
    private final SongService songService;

    /**
     * 下载歌曲到 RustFS（已缓存直接返回成功 + 文件下载链接）
     * POST /api/download/{sourceId}
     */
    @PostMapping("/{sourceId}")
    @Operation(summary = "下载歌曲到 RustFS（已缓存则直接返回文件链接）")
    public Result<Map<String, Object>> download(
            @PathVariable @Parameter(description = "歌曲ID") String sourceId,
            @RequestBody @Parameter(description = "歌曲信息") Map<String, Object> params) {

        String name = (String) params.getOrDefault("name", sourceId);
        String artist = (String) params.getOrDefault("artist", "未知歌手");
        String album = (String) params.getOrDefault("album", "");
        String coverUrl = (String) params.getOrDefault("coverUrl", "");
        Integer duration = params.get("duration") instanceof Number n ? n.intValue() : 0;
        String level = (String) params.getOrDefault("level", "exhigh");

        try {
            downloadService.download(sourceId, name, artist, album, coverUrl, duration, level);
        } catch (Exception e) {
            // 如果已经缓存，忽略错误
            if (!storageService.exists("songs/" + sourceId + ".mp3")) {
                return Result.error("下载失败: " + e.getMessage());
            }
        }

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("sourceId", sourceId);
        data.put("fileUrl", "/api/download/file/" + sourceId);
        data.put("cached", true);
        return Result.ok(data);
    }

    /**
     * 从 RustFS 读取文件流式返回浏览器（触发浏览器下载）
     * GET /api/download/file/{sourceId}
     */
    @GetMapping("/file/{sourceId}")
    @Operation(summary = "从 RustFS 下载文件到浏览器")
    public void fileDownload(@PathVariable String sourceId, HttpServletResponse response) {
        String objectName = "songs/" + sourceId + ".mp3";
        // 查询歌曲名用于下载文件名
        Song song = songService.getBySourceId(sourceId);
        String fileName = (song != null && song.getName() != null && !song.getName().isEmpty())
                ? sanitizeFileName(song.getArtist() + " - " + song.getName())
                : sourceId;

        try (InputStream in = storageService.getObject(objectName);
             OutputStream out = response.getOutputStream()) {

            response.setContentType("audio/mpeg");
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(fileName + ".mp3", "UTF-8"));
            response.setHeader("Cache-Control", "public, max-age=86400");

            StreamUtils.copy(in, out);
        } catch (Exception e) {
            if (!response.isCommitted()) {
                response.setStatus(404);
            }
        }
    }

    /** 清理文件名中的非法字符 */
    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 检查歌曲是否已在 RustFS 缓存
     */
    @GetMapping("/check/{sourceId}")
    @Operation(summary = "检查歌曲是否已缓存")
    public Result<Boolean> check(@PathVariable String sourceId) {
        return Result.ok(storageService.exists("songs/" + sourceId + ".mp3"));
    }
}
