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
 * 音频流播放控制器 — 热歌本地缓存 + MinIO 直读 + 远程 URL 代理
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
    private final HotCacheService hotCacheService;
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

    /**
     * 播放前解析：返回「最终会播的那一首」的元信息（平台/曲目/封面/时长/是否替代版本）。
     *
     * <p>客户端拿到后应使用返回的 platform+sourceId 请求 {@code /stream?exact=true}，
     * 并把 {@code matchVersion} 拼进流地址，让本地缓存随解析结果一起失效。</p>
     */
    @GetMapping("/resolve")
    @Operation(summary = "解析播放来源（含替代版本信息）")
    public Result<SongPlayService.PlayResolution> resolve(
            @RequestParam String sourceId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String artist,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false, defaultValue = "") String coverUrl,
            @RequestParam(required = false) Long duration) {
        return Result.ok(songPlayService.resolve(sourceId, name, artist, platform, coverUrl, duration));
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

    /**
     * 兼容旧签名（单测与内部调用）：exact=false。没有 @GetMapping，Spring 不会注册成路由。
     */
    public void stream(String sourceId, String name, String artist, String platform,
                       HttpServletRequest request, HttpServletResponse response) {
        stream(sourceId, name, artist, platform, false, request, response);
    }

    /** 音频流代理 — MinIO 直读 → 远程 URL 代理 */
    @GetMapping("/stream")
    @Operation(summary = "代理音频流（支持 Range，MinIO 兜底）")
    public void stream(@RequestParam String sourceId,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String artist,
                       @RequestParam(required = false) String platform,
                       @RequestParam(required = false, defaultValue = "false") boolean exact,
                       HttpServletRequest request, HttpServletResponse response) {
        // 0) 热歌本地缓存：命中即从磁盘返回，与上游风控/直链过期彻底解耦
        java.nio.file.Path cached = hotCacheService.lookup(sourceId);
        if (cached != null) {
            try {
                serveHotCache(cached, sourceId, request, response);
                return;
            } catch (Exception e) {
                log.warn("热歌缓存文件不可用，回退代理: {}", e.getMessage());
                if (response.isCommitted()) return;
            }
        }
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
                        // 不可满足的 Range 必须回 416：把 start 夹到 fileSize-1 会伪造出
                        // 「Content-Range 起点与请求不一致」的假 206，客户端（Media3）拿到短 body 后
                        // 抛 ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE。这里不写 body。
                        if (start >= fileSize || end < start) {
                            response.setStatus(416);
                            response.setHeader("Content-Range", "bytes */" + fileSize);
                            return;
                        }
                        if (end > fileSize - 1) end = fileSize - 1;
                        long length = end - start + 1;
                        response.setStatus(206);
                        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                        response.setContentLengthLong(length);
                        try (InputStream in = storageService.getObjectRange(minioObjectName, start, length);
                             OutputStream out = response.getOutputStream()) { StreamUtils.copy(in, out); }
                    } else {
                        response.setContentLengthLong(fileSize);
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
        audioProxyService.proxy(sourceId, name, artist, platform, request, response, false, null, exact);
    }

    /** 热歌缓存元信息的读取器：只读多线程安全。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper META_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 本地缓存文件直出。
     *
     * <p>Range 语义与 MinIO 分支逐条对齐：不可满足的区间必须回 416，不能把 start 夹到
     * fileSize-1 伪造 206——Media3 拿到「起点与请求不符」的短 body 会抛
     * ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE，用户感知是"这首歌一点进度条就崩"。</p>
     */
    private void serveHotCache(java.nio.file.Path file, String sourceId,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        long fileSize = java.nio.file.Files.size(file);
        String rangeHeader = request.getHeader("Range");
        response.setContentType("audio/mpeg");
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, max-age=86400");
        applyHotCacheMeta(sourceId, response);

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileSize > 0) {
            long start = 0, end = fileSize - 1;
            String[] parts = rangeHeader.substring(6).split("-");
            if (parts.length > 0 && !parts[0].isEmpty()) start = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
            if (start >= fileSize || end < start) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + fileSize);
                return;
            }
            if (end > fileSize - 1) end = fileSize - 1;
            long length = end - start + 1;
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            response.setContentLengthLong(length);
            try (InputStream in = java.nio.file.Files.newInputStream(file)) {
                in.skipNBytes(start);
                copyExactly(in, response.getOutputStream(), length);
            }
            return;
        }
        response.setContentLengthLong(fileSize);
        try (InputStream in = java.nio.file.Files.newInputStream(file);
             OutputStream out = response.getOutputStream()) {
            StreamUtils.copy(in, out);
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) break;
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    /** 预热时写下的换源信息：命中缓存也要如实告诉客户端"你听的是替代版本"。 */
    private void applyHotCacheMeta(String sourceId, HttpServletResponse response) {
        java.nio.file.Path meta = hotCacheService.metaPath(sourceId);
        if (!java.nio.file.Files.isRegularFile(meta)) return;
        try {
            Map<?, ?> m = META_MAPPER.readValue(
                    java.nio.file.Files.readString(meta, java.nio.charset.StandardCharsets.UTF_8), Map.class);
            if (!Boolean.TRUE.equals(m.get("isSubstitute"))) return;
            String platform = str(m.get("platform"));
            if (!platform.isBlank()) response.setHeader("X-Play-Source", platform);
            response.setHeader("X-Play-Substitute", "1");
            AudioProxyService.setUtf8Header(response, "X-Play-Title", str(m.get("title")));
            AudioProxyService.setUtf8Header(response, "X-Play-Artist", str(m.get("artist")));
            AudioProxyService.setUtf8Header(response, "X-Play-Cover", str(m.get("coverUrl")));
            AudioProxyService.setUtf8Header(response, "X-Play-Reason", str(m.get("reason")));
            String quality = str(m.get("quality"));
            if (!quality.isBlank()) response.setHeader("X-Quality", quality);
        } catch (Exception e) {
            log.debug("热歌缓存元信息读取失败 {}: {}", sourceId, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

