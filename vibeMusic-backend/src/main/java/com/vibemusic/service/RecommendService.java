package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.SongMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final PlayHistoryMapper playHistoryMapper;
    private final SongMapper songMapper;
    private final SongSearchService songSearchService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final int RECOMMEND_COUNT = 8;
    private static final int RANDOM_BASE = 4; // 基础随机歌曲数，保证多样性
    private static final int HISTORY_DAYS = 30;
    private static final int HISTORY_SAMPLE = 100;
    private static final String CACHE_PREFIX = "recommend:v3:"; // v3: 新算法
    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(30); // 30min，推荐更及时
    private static final Duration GUEST_CACHE_TTL = Duration.ofMinutes(10);

    /**
     * 个性化推荐入口
     * @param userId   用户ID（null=未登录）
     * @param deviceId 设备标识（未登录时用于缓存隔离）
     * @param refresh  是否跳过缓存强制刷新（如用户点击"换一批"）
     */
    public RecommendResult getPersonalized(Long userId, String deviceId, boolean refresh) {
        String cacheKey = userId != null
                ? CACHE_PREFIX + "user:" + userId
                : CACHE_PREFIX + "guest:" + (deviceId != null ? deviceId : "anon");

        // 非刷新模式：尝试读缓存
        if (!refresh) {
            RecommendResult cached = readCache(cacheKey);
            if (cached != null) {
                log.debug("推荐缓存命中: {}", cacheKey);
                return cached;
            }
        }

        // 执行推荐
        RecommendResult result;
        Duration ttl;
        try {
            if (userId != null) {
                result = buildPersonalized(userId);
                ttl = USER_CACHE_TTL;
            } else {
                result = buildGuest();
                ttl = GUEST_CACHE_TTL;
            }
        } catch (Exception e) {
            log.warn("推荐逻辑异常，降级到随机推荐", e);
            result = buildGuest();
            ttl = GUEST_CACHE_TTL;
        }

        writeCache(cacheKey, result, ttl);
        return result;
    }

    /** 兼容旧调用（默认不走刷新） */
    public RecommendResult getPersonalized(Long userId, String deviceId) {
        return getPersonalized(userId, deviceId, false);
    }

    /**
     * 删除用户推荐缓存（播放新歌后触发）
     */
    public void evictUserCache(Long userId) {
        try {
            String key = CACHE_PREFIX + "user:" + userId;
            stringRedisTemplate.delete(key);
            log.debug("删除推荐缓存: {}", key);
        } catch (Exception e) {
            log.warn("删除推荐缓存失败", e);
        }
    }

    // ================== 私有方法 ==================

    /**
     * 已登录用户个性化推荐（v3: 随机打底 + 兴趣扩展，保证多样性）
     */
    private RecommendResult buildPersonalized(Long userId) {
        // 获取近30天播放记录
        LocalDateTime since = LocalDateTime.now().minusDays(HISTORY_DAYS);
        List<PlayHistory> history = playHistoryMapper.selectList(
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId)
                        .ge(PlayHistory::getPlayedAt, since)
                        .orderByDesc(PlayHistory::getPlayedAt)
                        .last("LIMIT " + HISTORY_SAMPLE));

        // 已听过的 sourceId 集合（用于去重）
        Set<String> playedIds = history.stream()
                .map(PlayHistory::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> seenSourceIds = new HashSet<>(playedIds);

        List<SongDTO> result = new ArrayList<>();

        // ① 随机打底：保证多样性
        try {
            List<SongDTO> random = songSearchService.getRandomSongs(RANDOM_BASE);
            for (SongDTO s : random) {
                if (s.getSourceId() != null && seenSourceIds.add(s.getSourceId())) {
                    if (s.getDuration() == null || s.getDuration() > 30) {
                        result.add(s);
                    }
                }
            }
        } catch (Exception e) { log.warn("随机推荐获取失败", e); }

        // ② 兴趣扩展：基于常听歌手搜歌
        if (result.size() < RECOMMEND_COUNT && !history.isEmpty()) {
            Map<String, Long> artistWeight = history.stream()
                    .filter(h -> h.getArtist() != null && !h.getArtist().isEmpty())
                    .collect(Collectors.groupingBy(PlayHistory::getArtist, Collectors.counting()));
            List<String> topArtists = artistWeight.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3) // 只取前3，搜索引擎会返回多样性结果
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            for (String artist : topArtists) {
                if (result.size() >= RECOMMEND_COUNT) break;
                try {
                    List<SongDTO> songs = songSearchService.search(artist, 1, 10, null).getList();
                    for (SongDTO s : songs) {
                        if (result.size() >= RECOMMEND_COUNT) break;
                        if (s.getSourceId() != null && seenSourceIds.add(s.getSourceId())) {
                            if (s.getDuration() != null && s.getDuration() <= 30) continue;
                            result.add(s);
                        }
                    }
                } catch (Exception e) { log.warn("搜索歌手 {} 失败", artist, e); }
            }
        }

        // ③ 仍有空缺 → 补随机
        if (result.size() < RECOMMEND_COUNT) {
            try {
                List<SongDTO> supplement = songSearchService.getRandomSongs(RECOMMEND_COUNT - result.size());
                for (SongDTO s : supplement) {
                    if (result.size() >= RECOMMEND_COUNT) break;
                    if (s.getSourceId() != null && seenSourceIds.add(s.getSourceId())) {
                        if (s.getDuration() != null && s.getDuration() <= 30) continue;
                        result.add(s);
                    }
                }
            } catch (Exception e) { log.warn("补充随机歌曲失败", e); }
        }

        // 仍无结果 → 全随机兜底
        if (result.isEmpty()) {
            return buildGuestResult("最近没有听过歌？试试这些吧~");
        }

        Collections.shuffle(result);
        markOfflineStatus(result);

        return RecommendResult.builder()
                .songs(result)
                .greeting("根据你喜爱的歌曲，为你推荐~")
                .type("personalized")
                .build();
    }

    /**
     * 未登录游客推荐
     */
    private RecommendResult buildGuest() {
        return buildGuestResult("登录后享受个性化推荐~");
    }

    private RecommendResult buildGuestResult(String greeting) {
        try {
            List<SongDTO> songs = songSearchService.getRandomSongs(RECOMMEND_COUNT);
            markOfflineStatus(songs);
            return RecommendResult.builder()
                    .songs(songs)
                    .greeting(greeting)
                    .type("random")
                    .build();
        } catch (Exception e) {
            log.error("随机推荐失败", e);
            return RecommendResult.builder()
                    .songs(Collections.emptyList())
                    .greeting("推荐服务暂时不可用")
                    .type("random")
                    .build();
        }
    }

    /**
     * 生成推荐理由（基于播放历史的"温度"）
     */
    private String buildReason(List<PlayHistory> history) {
        if (history.isEmpty()) return null;

        // 找播放次数最多的歌
        Map<String, Long> songCount = history.stream()
                .collect(Collectors.groupingBy(
                        h -> h.getSongName() != null ? h.getSongName() : "未知歌曲",
                        Collectors.counting()));
        String topSong = songCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        // 找最常听的歌手
        Map<String, Long> artistCount = history.stream()
                .filter(h -> h.getArtist() != null)
                .collect(Collectors.groupingBy(PlayHistory::getArtist, Collectors.counting()));
        String topArtist = artistCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        if (topSong != null && topArtist != null && songCount.get(topSong) >= 3) {
            return "因为你最近喜欢" + topArtist + "的《" + topSong + "》，为你推荐同风格歌曲";
        }
        if (topArtist != null) {
            return "基于你常听" + topArtist + "的歌曲，为你推荐";
        }
        return "根据你的播放历史，为你推荐";
    }

    /**
     * 生成动态欢迎语
     */
    private String buildGreeting(int historyCount, List<String> topArtists) {
        if (historyCount == 0) return "最近没有听过歌？试试这些吧~";
        if (topArtists.isEmpty()) return "为你推荐一些好听的歌~";
        String artist = topArtists.get(0);
        if (historyCount < 5) return "最近在听 " + artist + "？试试这些~";
        if (historyCount < 20) return "根据你的口味，为你推荐~";
        return "你常听 " + artist + "，这些应该也会喜欢~";
    }

    /**
     * 标记歌曲离线缓存状态 — 通过 DB song 表批量查询（消除 N 次 MinIO statObject HTTP 调用）
     * <p>
     * 逻辑：song.url != null → 歌曲已下载到 RustFS
     */
    private void markOfflineStatus(List<SongDTO> songs) {
        if (songs.isEmpty()) return;
        try {
            List<String> ids = songs.stream()
                    .map(SongDTO::getSourceId).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (ids.isEmpty()) return;
            // 一次 DB 查询替代 N 次 MinIO HTTP statObject
            Set<String> cachedIds = songMapper.selectList(
                    new LambdaQueryWrapper<Song>()
                            .in(Song::getSourceId, ids)
                            .isNotNull(Song::getUrl)
                            .select(Song::getSourceId))
                    .stream().map(Song::getSourceId)
                    .collect(Collectors.toSet());
            for (SongDTO s : songs) {
                s.setCached(s.getSourceId() != null && cachedIds.contains(s.getSourceId()));
            }
        } catch (Exception e) {
            log.warn("批量查询缓存状态失败，回退到逐个检查 MinIO", e);
            // 降级兜底：仅异常时逐个查 MinIO
            for (SongDTO s : songs) {
                if (s.getSourceId() != null) {
                    try { s.setCached(storageService.exists("songs/" + s.getSourceId() + ".mp3")); }
                    catch (Exception ex) { s.setCached(false); }
                }
            }
        }
    }

    /**
     * 检查某首歌是否已缓存到 RustFS（排序使用，通过 DB 查询）
     */
    private boolean checkCached(String sourceId) {
        if (sourceId == null) return false;
        try {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, sourceId)
                    .isNotNull(Song::getUrl)
                    .select(Song::getId));
            return song != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读 Redis 缓存 + 校验：单平台全覆盖 = 缓存污染，自动清理
     */
    private RecommendResult readCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            RecommendResult result = objectMapper.readValue(json, RecommendResult.class);
            if (isCachePoisoned(result)) {
                log.warn("检测到推荐缓存污染(单一平台), 自动清理: {} ({}首全{})",
                        key, result.getSongs().size(),
                        result.getSongs().get(0).getPlatform());
                stringRedisTemplate.delete(key);
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("读取推荐缓存失败: {}", key, e);
            return null;
        }
    }

    /**
     * 检测缓存是否被污染：随机推荐 8 首全来自同一平台 → 另一个平台当时挂了
     */
    private boolean isCachePoisoned(RecommendResult result) {
        List<SongDTO> songs = result.getSongs();
        if (songs == null || songs.size() < 4) return false;
        long neteaseCount = songs.stream().filter(s -> "netease".equals(s.getPlatform())).count();
        long qqCount = songs.stream().filter(s -> "qq".equals(s.getPlatform())).count();
        // 全部来自一个平台且数量≥4 → 大概率另一个平台当时异常
        return (neteaseCount == songs.size() || qqCount == songs.size());
    }

    /**
     * 写 Redis 缓存
     */
    private void writeCache(String key, RecommendResult result, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(key, json, ttl);
            log.debug("推荐缓存写入: {}, TTL={}", key, ttl);
        } catch (Exception e) {
            log.warn("写入推荐缓存失败: {}", key, e);
        }
    }
}
