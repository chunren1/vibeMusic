package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * 音频流播放控制器 — MinIO 直读 + 远程 URL 代理
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
    private final RestClient.Builder restClientBuilder;

    private RestClient restClient;

    @PostConstruct
    void initRestClient() {
        this.restClient = restClientBuilder.build();
    }

    private static final ExecutorService ASYNC_CACHE_EXECUTOR = new ThreadPoolExecutor(
            2, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            r -> { Thread t = new Thread(r, "async-cache-cleaner"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    @jakarta.annotation.PreDestroy
    public void shutdown() { ASYNC_CACHE_EXECUTOR.shutdown(); }

    /** 音频 CDN 域名通配符白名单（防 SSRF），支持 *.music.126.net 风格 */
    private static final List<String> AUDIO_CDN_WILDCARDS = List.of(
            "*.music.126.net",
            "*.gtimg.cn",
            "*.stream.qqmusic.qq.com",
            "*.tc.qq.com",
            "*.tencentmusic.com"
    );

    private boolean isCdnWhitelisted(String host) {
        if (host == null) return false;
        for (String pattern : AUDIO_CDN_WILDCARDS) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1); // .music.126.net
                if (host.equals(pattern.substring(2)) || host.endsWith(suffix)) return true;
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
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
            CompletableFuture.runAsync(() -> {
                try { recommendService.evictUserCache(userId); } catch (Exception ignored) {}
            }, ASYNC_CACHE_EXECUTOR);
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
                    response.setHeader("Cache-Control", "public, max-age=86400");

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
        streamFromRemote(sourceId, name, artist, platform, request, response);
    }

    private void streamFromRemote(String sourceId, String name, String artist,
                                  String platform, HttpServletRequest request,
                                  HttpServletResponse response) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                String audioUrl = songPlayService.getPlayUrl(sourceId, name, artist, platform);
                if (audioUrl == null) {
                    if (attempt < 5) { log.info("streamFromRemote: playUrl=null, retry {}/5: sourceId={}", attempt, sourceId); continue; }
                    response.setStatus(404); return;
                }
                String host = URI.create(audioUrl).getHost();
                if (!isCdnWhitelisted(host)) {
                    log.warn("SSRF blocked: {} (sourceId={})", host, sourceId);
                    response.setStatus(403); return;
                }
                String rangeHeader = request.getHeader("Range");
                restClient.get().uri(audioUrl).headers(h -> {
                    h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    if (rangeHeader != null) h.set("Range", rangeHeader);
                }).exchange((clientReq, clientResp) -> {
                    int cdnStatus = clientResp.getStatusCode().value();
                    // CDN URL 过期 (403) 或资源不存在 (404) → 抛异常触发重试获取新 URL
                    if (cdnStatus == 403 || cdnStatus == 404) {
                        throw new RuntimeException("CDN returned " + cdnStatus + ", will retry with fresh URL");
                    }
                    String ct = clientResp.getHeaders().getFirst("Content-Type");
                    response.setContentType(ct != null && ct.startsWith("audio/") ? ct : "audio/mpeg");
                    long cl = clientResp.getHeaders().getContentLength();
                    if (cl > 0) response.setContentLength((int) cl);
                    response.setHeader("Accept-Ranges", "bytes");
                    response.setHeader("Cache-Control", "public, max-age=3600");
                    if (clientResp.getStatusCode().is2xxSuccessful()) {
                        response.setStatus(cdnStatus);
                        if (cdnStatus == 206) {
                            String cr = clientResp.getHeaders().getFirst("Content-Range");
                            if (cr != null) response.setHeader("Content-Range", cr);
                        }
                    }
                    try (InputStream in = clientResp.getBody();
                         OutputStream out = response.getOutputStream()) { StreamUtils.copy(in, out); }
                    return null;
                });
                return;
            } catch (Exception e) {
                if (attempt < 3 && !response.isCommitted()) {
                    log.warn("音频流代理重试 ({}/{}) sourceId={}: {}", attempt, 3, sourceId, e.getMessage());
                } else {
                    log.error("音频流代理最终失败 sourceId={}: {}", sourceId, e.getMessage());
                    if (!response.isCommitted()) response.setStatus(500);
                }
            }
        }
    }
}
