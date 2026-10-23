package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.entity.Song;
import com.vibemusic.service.AudioProxyService;
import com.vibemusic.service.DownloadService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 下载控制器
 *
 * <p>两条链路：对象存储（MinIO，可选）命中时直读；存储未启用/不可用/对象缺失时，
 * 委托 {@link AudioProxyService} 从上游 CDN 直连流式转发（唯一代理实现，与播放共用）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
@Tag(name = "下载", description = "歌曲下载（对象存储缓存或上游直连代理）")
public class DownloadController {

    private final DownloadService downloadService;
    private final StorageService storageService;
    private final SongService songService;
    private final AudioProxyService audioProxyService;

    /**
     * 触发下载（存储可用则缓存到 MinIO；不可用则只解析播放地址并入库）
     * POST /api/download/{sourceId}
     */
    @PostMapping("/{sourceId}")
    @Operation(summary = "触发歌曲下载（对象存储可用则缓存，否则仅登记并返回下载链接）")
    public Result<Map<String, Object>> download(
            @PathVariable @Parameter(description = "歌曲ID") String sourceId,
            @RequestBody @Parameter(description = "歌曲信息") Map<String, Object> params) {

        String name = (String) params.getOrDefault("name", sourceId);
        String artist = params.containsKey("artist") && params.get("artist") != null
                ? (String) params.get("artist") : "未知歌手";
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
        // 该 URL 永远可用：存储有对象→直读，否则→上游直连代理
        data.put("fileUrl", "/api/download/file/" + sourceId);
        data.put("cached", storageService.exists("songs/" + sourceId + ".mp3"));
        return Result.ok(data);
    }

    /**
     * 下载文件到浏览器：MinIO 命中则直读，否则上游直连代理（带 attachment 头）
     * GET /api/download/file/{sourceId}
     */
    @GetMapping("/file/{sourceId}")
    @Operation(summary = "下载文件到浏览器（对象存储直读或上游直连代理）")
    public void fileDownload(@PathVariable String sourceId,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        String objectName = "songs/" + sourceId + ".mp3";
        String fileName = resolveFileName(sourceId);

        if (storageService.isAvailable() && storageService.exists(objectName)) {
            try (InputStream in = storageService.getObject(objectName);
                 OutputStream out = response.getOutputStream()) {

                response.setContentType("audio/mpeg");
                response.setHeader("Content-Disposition", contentDisposition(fileName));
                response.setHeader("Cache-Control", "private, max-age=86400");

                StreamUtils.copy(in, out);
                return;
            } catch (Exception e) {
                log.warn("对象存储直读失败，降级为上游直连代理: sourceId={}, err={}", sourceId, e.getMessage());
                if (response.isCommitted()) return;
            }
        }

        // 降级：存储未启用/不可用/对象不存在 → 从上游 CDN 直连流式转发
        audioProxyService.proxy(sourceId, null, null, null, request, response, true, fileName);
    }

    /** 查询歌曲名用于下载文件名（缺省用 sourceId）。 */
    private String resolveFileName(String sourceId) {
        Song song = songService.getBySourceId(sourceId);
        if (song != null && song.getName() != null && !song.getName().isEmpty()) {
            return sanitizeFileName((song.getArtist() != null ? song.getArtist() + " - " : "") + song.getName());
        }
        return sourceId;
    }

    /** 下载头：文件名 UTF-8 编码，避免中文乱码。 */
    private String contentDisposition(String fileName) {
        return "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName + ".mp3", StandardCharsets.UTF_8);
    }

    /** 清理文件名中的非法字符 */
    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 检查当前是否可以下载：对象存储已缓存 → true；存储不可用 → 也 true（走上游直连代理）。
     */
    @GetMapping("/check/{sourceId}")
    @Operation(summary = "检查当前是否可下载（对象存储已缓存，或存储不可用时走上游直连代理）")
    public Result<Boolean> check(@PathVariable String sourceId) {
        boolean cached = storageService.exists("songs/" + sourceId + ".mp3");
        return Result.ok(cached || !storageService.isAvailable());
    }
}
