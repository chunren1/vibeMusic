package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 音频流播放控制器 — MinIO 直读 + 远程 URL 代理
 *
 * <p>远程代理实现已收敛到 {@link AudioProxyService}（唯一实现），此处仅委托，
 * 避免「同一逻辑孪生副本」问题。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
@Tag(name = "音频流", description = "音频流播放与代理")
public class StreamController {

    private final SongPlayService songPlayService;
    private final PlayHistoryService playHistoryService;
    private final StorageService storageService;
    private final RecommendService recommendService;
    private final AudioProxyService audioProxyService;

    private static final ExecutorService ASYNC_CACHE_EXECUTOR = new ThreadPoolExecutor(
            2, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            r -> { Thread t = new Thread(r, "async-cache-cleaner"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    @jakarta.annotation.PreDestroy
    public void shutdown() { ASYNC_CACHE_EXECUTOR.shutdown(); }

    /** 代理请求头构造（委托 {@link AudioProxyService}，保留静态入口供既有调用方/测试使用）。 */
    static void applyProxyHeaders(HttpHeaders headers, String host, String rangeHeader) {
        AudioProxyService.applyProxyHeaders(headers, host, rangeHeader);
    }

    static boolean isBilibiliCdnHost(String host) {
        return AudioProxyService.isBilibiliCdnHost(host);
    }

    /** 代理总预算（委托 {@link AudioProxyService}）。 */
    static final long STREAM_TOTAL_BUDGET_MS = AudioProxyService.STREAM_TOTAL_BUDGET_MS;

    static boolean canRetryStream(int attempt, int maxAttempts, long deadlineMs, long nowMs) {
        return AudioProxyService.canRetryStream(attempt, maxAttempts, deadlineMs, nowMs);
    }

    /** 播放（记录历史 + 返回元信息） */
    @GetMapping("/play")
    @Operation(summary = "记录播放历史并返回元信息")
    public Result<Map<String, Object>> play(
            @RequestParam String sourceId, @RequestParam String name,
            @RequestParam(defaultValue = "未知歌手") String artist,
            @RequestParam(required = false, defaultValue = "") String coverUrl) {
        Long userId = UserService.getCurrentUserId();
        if (userId != null) {
            playHistoryService.record(userId, sourceId, name, artist, coverUrl);
            // evictUserCache 内部自带 try/catch + 日志，这里不再重复吞异常
            CompletableFuture.runAsync(() -> recommendService.evictUserCache(userId), ASYNC_CACHE_EXECUTOR);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("sourceId", sourceId);
        result.put("name", name);
        result.put("artist", artist);
        result.put("fromCache", storageService.exists("songs/" + sourceId + ".mp3"));
        return Result.ok(result);
    }

    /** 音频流代理 — MinIO 直读 → 远程 URL 代理 */
    @GetMapping("/stream")
    @Operation(summary = "代理音频流（支持 Range，MinIO 兜底）")
    public void stream(@RequestParam String sourceId,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String artist,
                       @RequestParam(required = false) String platform,
                       HttpServletRequest request, HttpServletResponse response) {
        String minioObjectName = "songs/" + sourceId + ".mp3";
        try {
            if (storageService.exists(minioObjectName)) {
                try {
                    long fileSize = storageService.getObjectSize(minioObjectName);
                    String rangeHeader = request.getHeader("Range");
                    response.setContentType("audio/mpeg");
                    response.setHeader("Accept-Ranges", "bytes");
                    response.setHeader("Cache-Control", "private, max-age=86400");

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileSize > 0) {
                        long start = 0, end = fileSize - 1;
                        String rangeValue = rangeHeader.substring(6);
                        String[] parts = rangeValue.split("-");
                        if (parts.length > 0 && !parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                        if (start >= fileSize) start = fileSize - 1;
                        long length = end - start + 1;
                        response.setStatus(206);
                        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                        response.setContentLength((int) length);
                        try (InputStream in = storageService.getObjectRange(minioObjectName, start, length);
                             OutputStream out = response.getOutputStream()) { StreamUtils.copy(in, out); }
                    } else {
                        response.setContentLength((int) fileSize);
                        try (InputStream in = storageService.getObject(minioObjectName);
                             OutputStream out = response.getOutputStream()) { StreamUtils.copy(in, out); }
                    }
                    return;
                } catch (Exception e) {
                    log.warn("MinIO 直读失败, 尝试远程代理: {}", e.getMessage());
                    if (response.isCommitted()) return;
                }
            }
        } catch (Exception e) {
            log.debug("MinIO unavailable, falling back to remote proxy: {}", e.getMessage());
        }
        // 上游直连代理：唯一实现在 AudioProxyService
        audioProxyService.proxy(sourceId, name, artist, platform, request, response, false, null);
    }
}
