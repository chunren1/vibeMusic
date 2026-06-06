package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.NeteaseApiService;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.SongService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
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

    /** 搜索（v2：独立平台搜索 + 去重合并 + 排序打分 + 分页） */
    @GetMapping("/search")
    @Operation(summary = "搜索歌曲（Redis缓存 + 独立QQ/网易云搜索 + 去重打分）")
    public Result<List<SongDTO>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(songService.search(keyword, page, size));
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
    @Operation(summary = "获取歌曲歌词（自动识别网易云/QQ平台）")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> lyric(@RequestParam String sourceId) {
        try {
            // 判断平台：包含字母 → QQ音乐ID，纯数字 → 网易云ID
            boolean isQQ = sourceId.matches(".*[a-zA-Z]+.*");
            Map<String, Object> result;
            String lyricStr;

            if (isQQ) {
                // QQ音乐歌词：通过 /qq/lyric?songmid=xxx 获取
                result = neteaseApiService.getQQLyric(sourceId);
                if (result == null) return Result.ok(List.of());
                // QQ返回格式: {code:200, data:{lyric:"...", trans:"..."}}
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) return Result.ok(List.of());
                lyricStr = (String) data.get("lyric");
            } else {
                // 网易云歌词：通过 /lyric?id=xxx 获取
                result = neteaseApiService.getLyric(sourceId);
                if (result == null) return Result.ok(List.of());
                Map<String, Object> lrc = (Map<String, Object>) result.get("lrc");
                if (lrc == null) return Result.ok(List.of());
                lyricStr = (String) lrc.get("lyric");
            }

            if (lyricStr == null || lyricStr.isEmpty()) return Result.ok(List.of());

            // 解析 LRC 格式 → [{time, text}]
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
            log.info("歌词解析: sourceId={} platform={} lines={}", sourceId, isQQ ? "QQ" : "Netease", lines.size());
            return Result.ok(lines);
        } catch (Exception e) {
            log.error("获取歌词失败: {}", e.getMessage());
            return Result.ok(List.of());
        }
    }

    /**
     * 音频流代理 — 解决 Web Audio API CORS 限制
     * 后端从网易云/QQ 获取音频然后流式返回给前端，同源访问，无跨域问题
     */
    @GetMapping("/stream")
    @Operation(summary = "代理音频流（支持Range请求）")
    public void stream(@RequestParam String sourceId,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            String audioUrl = songService.getPlayUrl(sourceId);
            if (audioUrl == null) {
                response.setStatus(404);
                return;
            }

            URL url = new URL(audioUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            // 转发 Range 请求头（支持 seek）
            String rangeHeader = request.getHeader("Range");
            if (rangeHeader != null) {
                conn.setRequestProperty("Range", rangeHeader);
            }

            conn.connect();
            int contentLength = conn.getContentLength();
            String contentType = conn.getContentType();
            int statusCode = conn.getResponseCode();

            // 设置响应头
            if (contentType != null && contentType.startsWith("audio/")) {
                response.setContentType(contentType);
            } else {
                response.setContentType("audio/mpeg");
            }
            if (contentLength > 0) response.setContentLength(contentLength);
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "public, max-age=3600");

            if (statusCode >= 200 && statusCode < 300) {
                response.setStatus(statusCode);
                if (statusCode == 206) {
                    response.setHeader("Content-Range",
                        conn.getHeaderField("Content-Range"));
                }
            } else {
                response.setStatus(200);
            }

            in = conn.getInputStream();
            out = response.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();

        } catch (Exception e) {
            log.error("音频流代理失败 sourceId={}: {}", sourceId, e.getMessage());
            if (!response.isCommitted()) {
                response.setStatus(500);
            }
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }
}
