package com.vibemusic.service;

import com.vibemusic.config.AudioQualityTier;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲播放服务
 * <p>
 * 音质降级链：MinIO 缓存 → 原平台 API（多级音质）→ 跨平台降级 → DB 历史 URL 兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongPlayService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String MINIO_CACHE_PREFIX = "minio:exists:v1:";
    private static final Duration MINIO_CACHE_TTL = Duration.ofMinutes(10);
    /** 试听片段判定阈值（毫秒）：时长 ≤ 30 秒视为试听片段 */
    private static final int TRIAL_DURATION_MS = 30000;
    /** 音质并行探测线程池，3级同时调用避免串行等待 */
    private static final ExecutorService GET_URL_EXECUTOR = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "get-url-"); t.setDaemon(true); return t;
    });
    private final AtomicInteger degradationCount = new AtomicInteger(0);

    public int getDegradationCount() { return degradationCount.get(); }

    // ==================== getPlayInfo ====================

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId) {
        return getPlayInfo(sourceId, null, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId, String songName, String artist) {
        Map<String, Object> info = new HashMap<>();
        info.put("isTrial", false);
        info.put("platform", sourceId.matches("\\d+") ? "netease" : "qq");

        // 1. 优先 MinIO 本地缓存（Redis 缓存 exists 结果，TTL 10min，减少 MinIO statObject 调用）
        String minioObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInMinio(sourceId)) {
            String directUrl = storageService.getDirectUrl(minioObjectName);
            info.put("url", directUrl);
            info.put("fromCache", true);
            info.put("quality", AudioQualityTier.LOCAL.name());
            info.put("qualityLabel", AudioQualityTier.LOCAL.getLabel());
            info.put("degraded", false);
            log.info("音质[LOCAL] 歌曲 {} 命中 MinIO 缓存", sourceId);
            return info;
        }

        // 2. 在线获取：按 SLA 等级逐级降级（整体 8s 超时保护）
        final long DEADLINE = System.currentTimeMillis() + 8000;
        AudioQualityTier achievedTier = AudioQualityTier.FALLBACK;
        boolean degraded = false;

        try {
            if (sourceId.matches("\\d+")) {
                // 网易云音质降级：并行探测所有级别，按质量优先级取首个非试听结果
                // 优化前（串行）：P95 4.41s — HIRES→EXHIGH→HIGHER→STANDARD 逐级等待，4次API累计
                // 优化后（并行）：所有级别同时请求，最快可用结果 = max(单次API耗时)，P95 目标 < 2s
                AudioQualityTier[] tiers = {
                    AudioQualityTier.HIRES, AudioQualityTier.EXHIGH,
                    AudioQualityTier.HIGHER, AudioQualityTier.STANDARD
                };
                // 并行提交所有音质级别请求
                Map<AudioQualityTier, CompletableFuture<Map<String, Object>>> futures = new HashMap<>();
                for (AudioQualityTier tier : tiers) {
                    if (System.currentTimeMillis() > DEADLINE) break;
                    futures.put(tier, CompletableFuture.supplyAsync(() ->
                            neteaseApiService.getSongUrl(sourceId, tier.toNeteaseLevel())));
                }
                // 按音质优先级顺序检查结果（已完成的高优级别立即返回，未完成的等待但不再串行累积）
                for (AudioQualityTier tier : tiers) {
                    if (System.currentTimeMillis() > DEADLINE) {
                        log.warn("音质降级链超时: {}, 已检查至 {}", sourceId, tier.getLabel());
                        break;
                    }
                    CompletableFuture<Map<String, Object>> cf = futures.get(tier);
                    if (cf == null) continue;
                    try {
                        long remaining = Math.max(1, DEADLINE - System.currentTimeMillis());
                        Map<String, Object> result = cf.get(remaining, TimeUnit.MILLISECONDS);
                        if (result == null) continue;
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data == null || data.isEmpty()) continue;
                        String url = (String) data.get(0).get("url");
                        if (url == null || url.isEmpty()) continue;
                        Object trial = data.get(0).get("freeTrialInfo");
                        Object time = data.get(0).get("time");
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) {
                            degradationCount.incrementAndGet();
                            degraded = true;
                            log.info("音质降级: {} [{}] 为试听片段 → 尝试下一级", sourceId, tier.getLabel());
                            continue;
                        }
                        achievedTier = tier;
                        info.put("url", url);
                        info.put("quality", achievedTier.name());
                        info.put("qualityLabel", achievedTier.getLabel());
                        info.put("degraded", degraded);
                        log.info("音质[{}] 歌曲 {} 在线获取成功{}",
                            achievedTier.getLabel(), sourceId, degraded ? " (经并行降级)" : "");
                        return info;
                    } catch (TimeoutException e) {
                        log.info("音质[{}] 歌曲 {} 超时 → 尝试下一级", tier.getLabel(), sourceId);
                    } catch (Exception e) {
                        log.warn("音质[{}] 歌曲 {} 异常: {} → 尝试下一级", tier.getLabel(), sourceId, e.getMessage());
                    }
                }
                // 网易云全部降级为试听 → QQ降级（超时则跳过）
                if (System.currentTimeMillis() > DEADLINE) {
                    log.warn("音质降级链超时: {} 跳过QQ降级, 降级至试听", sourceId);
                } else {
                    degradationCount.incrementAndGet();
                    log.info("音质降级: {} 网易云全试听 → 尝试QQ降级", sourceId);
                    String qqUrl = tryQQFallback(songName, artist, sourceId);
                    if (qqUrl != null) {
                        achievedTier = AudioQualityTier.HIGHER;
                        info.put("url", qqUrl);
                        info.put("platform", "qq");
                        info.put("quality", AudioQualityTier.HIGHER.name());
                        info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                        info.put("degraded", true);
                        info.put("fallbackFrom", "netease-trial");
                        return info;
                    }
                } // end of DEADLINE else block
                achievedTier = AudioQualityTier.FALLBACK;
                info.put("isTrial", true);
                info.put("quality", AudioQualityTier.FALLBACK.name());
                info.put("qualityLabel", AudioQualityTier.FALLBACK.getLabel());
                info.put("degraded", true);
                Map<String, Object> f = neteaseApiService.getSongUrl(sourceId, "standard");
                if (f != null) {
                    List<Map<String, Object>> d = (List<Map<String, Object>>) f.get("data");
                    if (d != null && !d.isEmpty()) info.put("url", d.get(0).get("url"));
                }
            } else {
                // QQ 音乐 → 尝试获取 URL
                Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String qqUrl = (String) data.get(0).get("url");
                        if (qqUrl != null && !qqUrl.isEmpty()) {
                            info.put("url", qqUrl);
                            info.put("quality", AudioQualityTier.HIGHER.name());
                            info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                            info.put("degraded", false);
                            return info;
                        }
                    }
                }
                degradationCount.incrementAndGet();
                log.info("QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId);
                if (neteaseUrl != null) {
                    info.put("url", neteaseUrl);
                    info.put("platform", "netease");
                    info.put("quality", AudioQualityTier.HIGHER.name());
                    info.put("qualityLabel", AudioQualityTier.HIGHER.getLabel());
                    info.put("degraded", true);
                    info.put("fallbackFrom", "qq-no-url");
                    return info;
                }
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试 MinIO 兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 历史URL兜底（只查 url 列，避免读取 TEXT 歌词列）
        if (info.get("url") == null) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, sourceId)
                    .select(Song::getId, Song::getUrl));
            if (song != null && song.getUrl() != null) {
                info.put("url", song.getUrl());
                info.put("fromCache", true);
                info.put("quality", AudioQualityTier.STANDARD.name());
                info.put("qualityLabel", AudioQualityTier.STANDARD.getLabel());
                info.put("degraded", true);
                degradationCount.incrementAndGet();
                log.info("音质降级: {} API失败 → DB历史URL兜底", sourceId);
            }
        }

        if (!info.containsKey("quality")) {
            info.put("quality", AudioQualityTier.FALLBACK.name());
            info.put("qualityLabel", AudioQualityTier.FALLBACK.getLabel());
            info.put("degraded", true);
        }
        return info;
    }

    // ==================== getPlayUrl ====================

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
        return getPlayUrl(sourceId, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist) {
        return getPlayUrl(sourceId, songName, artist, null);
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist, String platform) {
        // 1. 优先检查 MinIO 缓存（Redis 缓存 exists 结果，TTL 10min）
        String minioObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInMinio(sourceId)) {
            String directUrl = storageService.getDirectUrl(minioObjectName);
            log.info("getPlayUrl: {} 命中 MinIO 缓存", sourceId);
            return directUrl;
        }

        // 2. 尝试从 API 获取
        boolean explicitQQ = "qq".equalsIgnoreCase(platform);
        boolean explicitNE = "netease".equalsIgnoreCase(platform);
        boolean guessNetEase = sourceId != null && sourceId.matches("\\d+");

        try {
            if (explicitQQ || (!explicitNE && !guessNetEase)) {
                try {
                    Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                    if (result != null) {
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data != null && !data.isEmpty()) {
                            String qqUrl = (String) data.get(0).get("url");
                            if (qqUrl != null && !qqUrl.isEmpty()) return qqUrl;
                        }
                    }
                } catch (Exception e) {
                    log.warn("getPlayUrl: QQ {} 获取失败: {}", sourceId, e.getMessage());
                }
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId);
                if (neteaseUrl != null) return neteaseUrl;
            } else {
                // 并行探测 3 级音质（exhigh/higher/standard），取最高可用非试听版本
                String[] levels = {"exhigh", "higher", "standard"};
                List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
                for (String level : levels) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            return neteaseApiService.getSongUrl(sourceId, level);
                        } catch (Exception e) {
                            log.debug("getPlayUrl: 网易云 {} level={} 失败: {}", sourceId, level, e.getMessage());
                            return null;
                        }
                    }, GET_URL_EXECUTOR));
                }
                String neUrl = null;
                boolean neAllFailed = true;
                for (int i = 0; i < levels.length && neUrl == null; i++) {
                    try {
                        Map<String, Object> result = futures.get(i).get(5, TimeUnit.SECONDS);
                        neAllFailed = false;
                        if (result == null) continue;
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data == null || data.isEmpty()) continue;
                        String url = (String) data.get(0).get("url");
                        if (url == null || url.isEmpty()) continue;
                        Object trial = data.get(0).get("freeTrialInfo");
                        Object time = data.get(0).get("time");
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) {
                            log.info("歌曲 {} 音质 {} 为试听, 尝试降级", sourceId, levels[i]);
                            continue;
                        }
                        neUrl = url;
                    } catch (Exception e) {
                        log.debug("getPlayUrl: level={} timeout/error: {}", levels[i], e.getMessage());
                    }
                }
                if (neUrl != null) return neUrl;
                if (neAllFailed) {
                    log.info("getPlayUrl: 歌曲 {} 网易云全失败，尝试QQ降级", sourceId);
                    String qqUrl = tryQQFallback(songName, artist, sourceId);
                    if (qqUrl != null) return qqUrl;
                }
                log.warn("歌曲 {} 所有平台均无可用播放链接", sourceId);
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试DB兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 兜底（只查 url 列）
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                .eq(Song::getSourceId, sourceId)
                .select(Song::getId, Song::getUrl));
        return song != null ? song.getUrl() : null;
    }

    // ==================== 跨平台降级 ====================

    /**
     * 从搜索结果中找最优匹配，基于词重叠率 + 歌名首字匹配
     */
    private Map<String, Object> findBestMatch(List<Map<String, Object>> results, String expectedName, String expectedArtist) {
        Map<String, Object> best = null;
        double bestScore = 0;
        String target = (expectedName + " " + (expectedArtist != null ? expectedArtist : "")).toLowerCase().trim();
        for (Map<String, Object> item : results) {
            String candName = item.get("name") != null ? String.valueOf(item.get("name")) : "";
            String candArtist = item.get("artists") != null ? String.valueOf(item.get("artists")) : "";
            String candidate = (candName + " " + candArtist).toLowerCase().trim();
            double score = similarity(target, candidate);
            // 歌名首字相同加分
            if (!candName.isEmpty() && !expectedName.isEmpty() && candName.charAt(0) == expectedName.charAt(0)) score += 0.2;
            if (score > bestScore) { bestScore = score; best = item; }
        }
        return bestScore > 0.15 ? best : (results.get(0)); // 分数太低降级取第一条
    }

    /** 简单 token 重叠率 */
    private double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("\\s+")));
        int overlap = 0;
        for (String s : setA) { if (setB.contains(s)) overlap++; }
        double jaccard = (double) overlap / Math.max(setA.size(), setB.size());
        // 包含关系 bonus
        if (a.contains(b) || b.contains(a)) return Math.max(jaccard, 0.6);
        return jaccard;
    }

    // ==================== 跨平台降级（续） ====================

    @SuppressWarnings("unchecked")
    private String tryQQFallback(String songName, String artist, String neteaseId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, neteaseId));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseApiService.searchQQ(keyword, 5);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            // 歌名/歌手相似度匹配，避免取到错误歌曲
            Map<String, Object> best = findBestMatch(data, songName, artist);
            if (best == null) { log.info("QQ降级: '{}' 无匹配歌曲", keyword); return null; }
            String qqSourceId = best.get("id") != null ? String.valueOf(best.get("id")) : null;
            String matchName = best.get("name") != null ? String.valueOf(best.get("name")) : "";
            String matchArtists = best.get("artists") != null ? String.valueOf(best.get("artists")) : "";
            if (qqSourceId == null) return null;
            Object durObj = best.get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= TRIAL_DURATION_MS) {
                log.info("QQ降级: '{}' 也只有试听版，跳过", matchName);
                return null;
            }
            Map<String, Object> urlResult = neteaseApiService.getQQSongUrl(qqSourceId);
            if (urlResult != null) {
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData != null && !urlData.isEmpty()) {
                    String url = (String) urlData.get(0).get("url");
                    if (url != null && !url.isEmpty()) {
                        log.info("QQ降级成功: '{}' → QQ '{}' (score={})", keyword, matchName, similarity(keyword, matchName + matchArtists));
                        return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }

    /**
     * 检查歌曲是否缓存于 MinIO，Redis 缓存结果减少 MinIO HTTP 调用
     * 任何异常（Redis/MinIO 不可用）均返回 false，调用方继续走 API/DB 降级
     */
    private boolean isCachedInMinio(String sourceId) {
        if (sourceId == null) return false;
        try {
            String cacheKey = MINIO_CACHE_PREFIX + sourceId;
            // 先查 Redis
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if ("1".equals(cached)) return true;
            if ("0".equals(cached)) return false;

            // Redis 未命中 → 查 MinIO
            boolean exists = storageService.exists("songs/" + sourceId + ".mp3");
            // 写入 Redis 缓存（存在 10min，不存在 30s）
            Duration ttl = exists ? MINIO_CACHE_TTL : Duration.ofSeconds(30);
            stringRedisTemplate.opsForValue().set(cacheKey, exists ? "1" : "0", ttl);
            return exists;
        } catch (Exception e) {
            log.debug("isCachedInMinio failed for {} (Redis/MinIO unavailable): {}", sourceId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private String tryNeteaseFallback(String songName, String artist, String qqSourceId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, qqSourceId));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseApiService.searchNetease(keyword, 5);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            String neteaseId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (neteaseId == null) return null;
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= TRIAL_DURATION_MS) {
                log.info("网易云降级: {} 也只有试听版，跳过", keyword);
                return null;
            }
            String[] levels = {"exhigh", "higher", "standard"};
            for (String level : levels) {
                Map<String, Object> urlResult = neteaseApiService.getSongUrl(neteaseId, level);
                if (urlResult == null) continue;
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData == null || urlData.isEmpty()) continue;
                String url = (String) urlData.get(0).get("url");
                if (url == null || url.isEmpty()) continue;
                Object trial = urlData.get(0).get("freeTrialInfo");
                Object time = urlData.get(0).get("time");
                if (trial != null || (time instanceof Number && ((Number) time).intValue() <= TRIAL_DURATION_MS)) continue;
                log.info("网易云降级成功: {} → neteaseId={}, level={}", keyword, neteaseId, level);
                return url;
            }
            log.info("网易云降级: {} 所有音质均为试听，跳过", keyword);
        } catch (Exception e) {
            log.warn("网易云降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }
}
