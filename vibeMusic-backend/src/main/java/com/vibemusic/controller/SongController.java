package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
@Tag(name = "歌曲", description = "搜索、播放、历史记录")
public class SongController {

    private final SongSearchService songSearchService;
    private final SongPlayService songPlayService;
    private final PlayHistoryService playHistoryService;
    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final RecommendService recommendService;
    private final ESSearchService esSearchService;
    private final RestClient.Builder restClientBuilder;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 共享 HTTP 客户端（复用 Apache HttpClient 5 连接池） */
    private RestClient restClient;

    @PostConstruct
    void initRestClient() {
        this.restClient = restClientBuilder.build();
    }

    private static final String BANNER_CACHE_KEY = "banner:v2:home";
    private static final java.time.Duration BANNER_TTL = java.time.Duration.ofHours(2);

    /** Banner 轮播（网易云推荐歌单，Redis 缓存 2h） */
    @GetMapping("/banner")
    @Operation(summary = "首页轮播图")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> banner() {
        // 1. 查缓存
        try {
            String cached = stringRedisTemplate.opsForValue().get(BANNER_CACHE_KEY);
            if (cached != null) {
                return Result.ok(objectMapper.readValue(cached, List.class));
            }
        } catch (Exception ignored) {}

        try {
            Map<String, Object> result = neteaseApiService.personalizedPlaylists(5);
            List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("result");
            if (list == null) return Result.ok(List.of());

            List<Map<String, Object>> banners = list.stream().map(p -> {
                Map<String, Object> b = new HashMap<>();
                b.put("name", String.valueOf(p.getOrDefault("name", "")));
                b.put("coverUrl", String.valueOf(p.getOrDefault("picUrl", "")).replace("http://", "https://"));
                b.put("desc", String.valueOf(p.getOrDefault("copywriter", "精选歌单")));
                b.put("playCount", p.getOrDefault("playCount", 0));
                return b;
            }).collect(Collectors.toList());

            // 2. 写缓存
            try {
                stringRedisTemplate.opsForValue().set(BANNER_CACHE_KEY, objectMapper.writeValueAsString(banners), BANNER_TTL);
            } catch (Exception ignored) {}

            return Result.ok(banners);
        } catch (Exception e) {
            return Result.ok(List.of());
        }
    }

    /** 搜索（v2：独立平台搜索 + 去重合并 + 排序打分 + 分页 + 分源） */
    @GetMapping("/search")
    @Operation(summary = "搜索歌曲（三级缓存：Redis → ES → musicapi），返回 SearchResult 含 total/hasMore/source")
    public Result<SearchResult> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "40") int size,
            @RequestParam(required = false) String platform) {
        return Result.ok(songSearchService.search(keyword, page, size, platform));
    }

    /** ES 健康检查 — 用于验证 ES 连接状态 & 索引数据 */
    @GetMapping("/es-health")
    @Operation(summary = "ES 健康检查（集群状态 + 索引文档数 + 可用性）")
    public Result<Map<String, Object>> esHealth() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> cluster = esSearchService.healthCheck();
        result.put("available", esSearchService.isAvailable());
        if (cluster != null) {
            result.put("cluster", cluster);
            result.put("status", "connected");
        } else {
            result.put("status", "unreachable");
            result.put("message", "ES 不可达，搜索缓存已自动降级（不影响搜索功能）");
        }
        return Result.ok(result);
    }

    /** 随机推荐 */
    @GetMapping("/random")
    @Operation(summary = "随机推荐歌曲")
    public Result<List<SongDTO>> randomSongs(@RequestParam(defaultValue = "8") int count) {
        return Result.ok(songSearchService.getRandomSongs(count));
    }

    /** 播放（获取 URL + 试听标识 + 平台 + 记录历史） */
    @GetMapping("/play")
    @Operation(summary = "记录播放历史并返回元信息（URL解析由stream端点负责）")
    public Result<Map<String, Object>> play(
            @RequestParam String sourceId,
            @RequestParam String name,
            @RequestParam(defaultValue = "未知歌手") String artist,
            @RequestParam(required = false, defaultValue = "") String coverUrl) {

        // 1. 记录播放历史（仅登录用户）
        Long userId = UserService.getCurrentUserId();
        if (userId != null) {
            playHistoryService.record(userId, sourceId, name, artist, coverUrl);
            CompletableFuture.runAsync(() -> {
                try { recommendService.evictUserCache(userId); } catch (Exception ignored) {}
            });
        }

        // 2. 返回元信息（URL解析留给stream端点，避免重复请求）
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("sourceId", sourceId);
        result.put("name", name);
        result.put("artist", artist);
        result.put("fromCache", storageService.exists("songs/" + sourceId + ".mp3"));
        return Result.ok(result);
    }

    /** 最近播放 */
    @GetMapping("/history")
    @Operation(summary = "最近播放列表")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "20") int count) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.ok(List.of());
        return Result.ok(playHistoryService.recent(userId, count));
    }

    /** 导出播放历史 */
    @GetMapping("/history/export")
    @Operation(summary = "导出播放历史")
    public Result<Map<String, Object>> exportHistory() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.ok(playHistoryService.export(userId));
    }

    /** 批量删除播放历史 */
    @PostMapping("/history/remove")
    @Operation(summary = "批量删除播放历史")
    public Result<Integer> removeHistory(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        @SuppressWarnings("unchecked")
        List<String> sourceIds = (List<String>) body.get("sourceIds");
        if (sourceIds == null || sourceIds.isEmpty()) return Result.ok(0);
        int count = playHistoryService.deleteBatch(userId, sourceIds);
        return Result.ok("已删除 " + count + " 条记录", count);
    }

    private static final String LYRIC_CACHE_PREFIX = "lyric:v2:";
    private static final java.time.Duration LYRIC_TTL = java.time.Duration.ofDays(365); // 歌词永久不变

    /** 获取歌词（Redis 缓存，歌词数据永不变，一次拉取永久复用） */
    @GetMapping("/lyric")
    @Operation(summary = "获取歌曲歌词（自动识别网易云/QQ平台）")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> lyric(@RequestParam String sourceId) {
        // 1. 查 Redis 缓存
        String cacheKey = LYRIC_CACHE_PREFIX + sourceId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if ("__EMPTY__".equals(cached)) return Result.ok(List.of());
                List<Map<String, Object>> lines = objectMapper.readValue(cached, List.class);
                return Result.ok(lines);
            }
        } catch (Exception e) {
            log.warn("读取歌词缓存失败: sourceId={}", sourceId);
        }

        // 2. 通过 API 获取
        try {
            boolean isQQ = sourceId.matches(".*[a-zA-Z]+.*");
            Map<String, Object> result;
            String lyricStr;

            if (isQQ) {
                result = neteaseApiService.getQQLyric(sourceId);
                if (result == null) { cacheEmpty(cacheKey); return Result.ok(List.of()); }
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) { cacheEmpty(cacheKey); return Result.ok(List.of()); }
                lyricStr = (String) data.get("lyric");
            } else {
                result = neteaseApiService.getLyric(sourceId);
                if (result == null) { cacheEmpty(cacheKey); return Result.ok(List.of()); }
                Map<String, Object> lrc = (Map<String, Object>) result.get("lrc");
                if (lrc == null) { cacheEmpty(cacheKey); return Result.ok(List.of()); }
                lyricStr = (String) lrc.get("lyric");
            }

            if (lyricStr == null || lyricStr.isEmpty()) { cacheEmpty(cacheKey); return Result.ok(List.of()); }

            List<Map<String, Object>> lines = parseLrc(lyricStr);
            log.info("歌词解析: sourceId={} platform={} lines={}", sourceId, isQQ ? "QQ" : "Netease", lines.size());

            // 3. 写入 Redis 缓存
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(lines), LYRIC_TTL);
            } catch (Exception e) { log.warn("写入歌词缓存失败: sourceId={}", sourceId); }

            return Result.ok(lines);
        } catch (Exception e) {
            log.error("获取歌词失败: {}", e.getMessage());
            return Result.ok(List.of());
        }
    }

    private void cacheEmpty(String cacheKey) {
        try { stringRedisTemplate.opsForValue().set(cacheKey, "__EMPTY__", java.time.Duration.ofHours(1)); }
        catch (Exception ignored) {}
    }

    private List<Map<String, Object>> parseLrc(String lyricStr) {
        List<Map<String, Object>> lines = new ArrayList<>();
        String[] parts = lyricStr.split("\\n");
        for (String line : parts) {
            line = line.trim();
            if (line.isEmpty()) continue;
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
        return lines;
    }

    /**
     * 音频流代理 — 解决 Web Audio API CORS 限制
     * 优先级：RustFS直读 → 远程URL代理（同时写入RustFS缓存）
     */
    @GetMapping("/stream")
    @Operation(summary = "代理音频流（支持Range请求，RustFS兜底）")
    public void stream(@RequestParam String sourceId,
                       @RequestParam(required = false) String name,
                       @RequestParam(required = false) String artist,
                       @RequestParam(required = false) String platform,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        // 1. 优先从 RustFS 直读（支持 Range 请求，seek 秒级响应）
        String rustfsObjectName = "songs/" + sourceId + ".mp3";
        try {
            if (storageService.exists(rustfsObjectName)) {
                try {
                    long fileSize = storageService.getObjectSize(rustfsObjectName);
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

                        try (InputStream in = storageService.getObjectRange(rustfsObjectName, start, length);
                             OutputStream out = response.getOutputStream()) {
                            StreamUtils.copy(in, out);
                        }
                    } else {
                        response.setContentLength((int) fileSize);
                        try (InputStream in = storageService.getObject(rustfsObjectName);
                             OutputStream out = response.getOutputStream()) {
                            StreamUtils.copy(in, out);
                        }
                    }
                    log.debug("stream from RustFS: {} (Range={})", sourceId, rangeHeader != null ? "yes" : "no");
                    return;
                } catch (Exception e) {
                    log.warn("RustFS直读失败, 尝试远程代理: {}", e.getMessage());
                    if (!response.isCommitted()) {
                        // 继续 try remote proxy
                    } else {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // RustFS 不可用时（如 MinIO 未启动），直接降级到远程代理
            log.debug("RustFS unavailable ({}), falling back to remote proxy", e.getMessage());
        }

        // 2. 远程 URL 代理 (使用 RestClient，自带连接池 + 超时控制)
        //    网易云 CDN URL 有时效性，首次失败后重新获取 URL 再试一次
        streamFromRemote(sourceId, name, artist, platform, request, response);
    }

    private void streamFromRemote(String sourceId, String name, String artist,
                                  String platform, HttpServletRequest request,
                                  HttpServletResponse response) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String audioUrl = songPlayService.getPlayUrl(sourceId, name, artist, platform);
                if (audioUrl == null) {
                    response.setStatus(404);
                    return;
                }

                String rangeHeader = request.getHeader("Range");

                restClient.get()
                        .uri(audioUrl)
                        .headers(h -> {
                            h.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                            if (rangeHeader != null) h.set("Range", rangeHeader);
                        })
                        .exchange((clientReq, clientResp) -> {
                            HttpStatusCode statusCode = clientResp.getStatusCode();

                            // 设置响应头
                            String contentType = clientResp.getHeaders().getFirst("Content-Type");
                            if (contentType != null && contentType.startsWith("audio/")) {
                                response.setContentType(contentType);
                            } else {
                                response.setContentType("audio/mpeg");
                            }
                            long contentLength = clientResp.getHeaders().getContentLength();
                            if (contentLength > 0) response.setContentLength((int) contentLength);
                            response.setHeader("Accept-Ranges", "bytes");
                            response.setHeader("Cache-Control", "public, max-age=3600");

                            if (statusCode.is2xxSuccessful()) {
                                response.setStatus(statusCode.value());
                                if (statusCode.value() == 206) {
                                    String cr = clientResp.getHeaders().getFirst("Content-Range");
                                    if (cr != null) response.setHeader("Content-Range", cr);
                                }
                            }

                            // 流拷贝
                            try (InputStream in = clientResp.getBody();
                                 OutputStream out = response.getOutputStream()) {
                                StreamUtils.copy(in, out);
                            }
                            return null;
                        });
                return; // 成功，退出

            } catch (Exception e) {
                if (attempt == 1 && !response.isCommitted()) {
                    log.warn("音频流首次代理失败 (attempt=1/2), sourceId={}, 重新获取URL: {}",
                            sourceId, e.getMessage());
                    // 第二次循环会重新 getPlayUrl 拿新鲜 URL
                } else {
                    log.error("音频流代理失败 sourceId={}: {}", sourceId, e.getMessage());
                    if (!response.isCommitted()) {
                        response.setStatus(500);
                    }
                    return;
                }
            }
        }
    }
}
