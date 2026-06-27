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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲播放服务
 * <p>
 * 音质降级链：RustFS 缓存 → 原平台 API（多级音质）→ 跨平台降级 → DB 历史 URL 兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongPlayService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String RUSTFS_CACHE_PREFIX = "rustfs:exists:v1:";
    private static final Duration RUSTFS_CACHE_TTL = Duration.ofMinutes(10);
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

        // 1. 优先 RustFS 本地缓存（Redis 缓存 exists 结果，TTL 10min，减少 MinIO statObject 调用）
        String rustfsObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInRustFS(sourceId)) {
            String directUrl = storageService.getDirectUrl(rustfsObjectName);
            info.put("url", directUrl);
            info.put("fromCache", true);
            info.put("quality", AudioQualityTier.LOCAL.name());
            info.put("qualityLabel", AudioQualityTier.LOCAL.getLabel());
            info.put("degraded", false);
            log.info("音质[LOCAL] 歌曲 {} 命中RustFS缓存", sourceId);
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
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) {
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
            log.warn("API获取播放链接失败, sourceId={}, 尝试RustFS兜底: {}", sourceId, e.getMessage());
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
        // 1. 优先检查 RustFS 缓存（Redis 缓存 exists 结果，TTL 10min）
        String rustfsObjectName = "songs/" + sourceId + ".mp3";
        if (isCachedInRustFS(sourceId)) {
            String directUrl = storageService.getDirectUrl(rustfsObjectName);
            log.info("getPlayUrl: {} 命中RustFS缓存", sourceId);
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
                String[] levels = {"exhigh", "higher", "standard"};
                for (String level : levels) {
                    try {
                        Map<String, Object> result = neteaseApiService.getSongUrl(sourceId, level);
                        if (result == null) continue;
                        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                        if (data == null || data.isEmpty()) continue;
                        String url = (String) data.get(0).get("url");
                        if (url == null || url.isEmpty()) continue;
                        Object trial = data.get(0).get("freeTrialInfo");
                        Object time = data.get(0).get("time");
                        if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) {
                            log.info("歌曲 {} 音质 {} 为试听, 尝试降级", sourceId, level);
                            continue;
                        }
                        return url;
                    } catch (Exception e) {
                        log.warn("getPlayUrl: 网易云 {} level={} 失败: {}", sourceId, level, e.getMessage());
                    }
                }
                log.info("getPlayUrl: 歌曲 {} 网易云全失败，尝试QQ降级", sourceId);
                String qqUrl = tryQQFallback(songName, artist, sourceId);
                if (qqUrl != null) return qqUrl;
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
            String qqSourceId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (qqSourceId == null) return null;
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= 30000) {
                log.info("QQ降级: {} 也只有试听版，跳过", keyword);
                return null;
            }
            Map<String, Object> urlResult = neteaseApiService.getQQSongUrl(qqSourceId);
            if (urlResult != null) {
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData != null && !urlData.isEmpty()) {
                    String url = (String) urlData.get(0).get("url");
                    if (url != null && !url.isEmpty()) {
                        log.info("QQ降级成功: {} → QQ sourceId={}", keyword, qqSourceId);
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
     * 检查歌曲是否缓存于 RustFS，Redis 缓存结果减少 MinIO HTTP 调用
     * 任何异常（Redis/MinIO 不可用）均返回 false，调用方继续走 API/DB 降级
     */
    private boolean isCachedInRustFS(String sourceId) {
        if (sourceId == null) return false;
        try {
            String cacheKey = RUSTFS_CACHE_PREFIX + sourceId;
            // 先查 Redis
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if ("1".equals(cached)) return true;
            if ("0".equals(cached)) return false;

            // Redis 未命中 → 查 MinIO
            boolean exists = storageService.exists("songs/" + sourceId + ".mp3");
            // 写入 Redis 缓存（存在 10min，不存在 30s）
            Duration ttl = exists ? RUSTFS_CACHE_TTL : Duration.ofSeconds(30);
            stringRedisTemplate.opsForValue().set(cacheKey, exists ? "1" : "0", ttl);
            return exists;
        } catch (Exception e) {
            log.debug("isCachedInRustFS failed for {} (Redis/MinIO unavailable): {}", sourceId, e.getMessage());
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
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= 30000) {
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
                if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) continue;
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
