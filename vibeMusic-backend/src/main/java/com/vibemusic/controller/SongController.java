package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 歌曲检索控制器 — 搜索、推荐、Banner、歌词
 */
@Slf4j
@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
@Tag(name = "歌曲", description = "搜索、Banner、歌词")
public class SongController {

    private final SongSearchService songSearchService;
    private final NeteaseApiService neteaseApiService;
    private final ESSearchService esSearchService;
    private final JsonCacheService cache;

    private static final String BANNER_CACHE_KEY = "banner:v2:home";
    private static final java.time.Duration BANNER_TTL = java.time.Duration.ofHours(2);

    /** Banner 轮播（网易云推荐歌单，Redis 缓存 2h） */
    @GetMapping("/banner")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> banner() {
        List<Map<String, Object>> cached = cache.getAsList(BANNER_CACHE_KEY);
        if (cached != null) return Result.ok(cached);

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

            cache.set(BANNER_CACHE_KEY, banners, BANNER_TTL);
            return Result.ok(banners);
        } catch (Exception e) {
            log.warn("获取 banner 失败: {}", e.getMessage());
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
        // count 直通 DB LIMIT（含 lyric TEXT）：钳制到 1..50，默认值 8 不变
        int safeCount = Math.max(1, Math.min(count, SongSearchService.MAX_RANDOM_COUNT));
        return Result.ok(songSearchService.getRandomSongs(safeCount));
    }

    // v2 键下已缓存条目时间轴偏斜（百分秒被当毫秒解析），换 v3 键自然淘汰旧缓存
    private static final String LYRIC_CACHE_PREFIX = "lyric:v3:";
    private static final java.time.Duration LYRIC_TTL = java.time.Duration.ofDays(365);

    @GetMapping("/lyric")
    @Operation(summary = "获取歌曲歌词（自动识别网易云/QQ平台）")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> lyric(@RequestParam String sourceId) {
        String cacheKey = LYRIC_CACHE_PREFIX + sourceId;
        List<Map<String, Object>> cached = cache.getAsList(cacheKey);
        if (cached != null) return Result.ok(cached);

        try {
            boolean isQQ = sourceId.matches(".*[a-zA-Z]+.*");
            Map<String, Object> result;
            String lyricStr;
            if (isQQ) {
                result = neteaseApiService.getQQLyric(sourceId);
                if (result == null) { cache.setEmpty(cacheKey, java.time.Duration.ofHours(1)); return Result.ok(List.of()); }
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) { cache.setEmpty(cacheKey, java.time.Duration.ofHours(1)); return Result.ok(List.of()); }
                lyricStr = (String) data.get("lyric");
            } else {
                result = neteaseApiService.getLyric(sourceId);
                if (result == null) { cache.setEmpty(cacheKey, java.time.Duration.ofHours(1)); return Result.ok(List.of()); }
                Map<String, Object> lrc = (Map<String, Object>) result.get("lrc");
                if (lrc == null) { cache.setEmpty(cacheKey, java.time.Duration.ofHours(1)); return Result.ok(List.of()); }
                lyricStr = (String) lrc.get("lyric");
            }
            if (lyricStr == null || lyricStr.isEmpty()) { cache.setEmpty(cacheKey, java.time.Duration.ofHours(1)); return Result.ok(List.of()); }
            List<Map<String, Object>> lines = parseLrc(lyricStr);
            log.info("歌词解析: sourceId={} platform={} lines={}", sourceId, isQQ ? "QQ" : "Netease", lines.size());
            cache.set(cacheKey, lines, LYRIC_TTL);
            return Result.ok(lines);
        } catch (Exception e) {
            log.error("获取歌词失败: {}", e.getMessage());
            return Result.ok(List.of());
        }
    }

    private static final Pattern LRC_PATTERN =
            Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d+))?\\](.*)");

    static List<Map<String, Object>> parseLrc(String lyricStr) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : lyricStr.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = LRC_PATTERN.matcher(line);
            if (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int ms = lrcFractionToMs(m.group(3));
                String text = m.group(4).trim();
                double time = min * 60 + sec + ms / 1000.0;
                Map<String, Object> item = new HashMap<>();
                item.put("time", time); item.put("text", text.isEmpty() ? "♪" : text);
                lines.add(item);
            }
        }
        return lines;
    }

    // LRC 小数部分按位数归一为毫秒：1 位=十分秒(x100)、2 位=百分秒(x10)、3 位=毫秒原值、更长截断到毫秒
    static int lrcFractionToMs(String frac) {
        if (frac == null || frac.isEmpty()) return 0;
        String f = frac.length() > 3 ? frac.substring(0, 3) : frac;
        int v = Integer.parseInt(f);
        if (f.length() == 1) return v * 100;
        if (f.length() == 2) return v * 10;
        return v;
    }
}
