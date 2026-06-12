package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final SongService songService;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final int RECOMMEND_COUNT = 8;
    private static final int HISTORY_DAYS = 30;
    private static final int HISTORY_SAMPLE = 100;
    private static final String CACHE_PREFIX = "recommend:v2:"; // v2 清旧缓存(ECONNRESET污染)
    private static final Duration USER_CACHE_TTL = Duration.ofHours(6);
    private static final Duration GUEST_CACHE_TTL = Duration.ofMinutes(10); // 游客推荐10分钟过期，加速API恢复后生效

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
     * 已登录用户个性化推荐
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

        if (history.isEmpty()) {
            log.info("用户{}无近期播放记录，降级随机推荐", userId);
            return buildGuestResult("最近没有听过歌？试试这些吧~");
        }

        // 已听过的 sourceId 集合（用于过滤）
        Set<String> playedIds = history.stream()
                .map(PlayHistory::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 按歌手聚合兴趣权重
        Map<String, Long> artistWeight = history.stream()
                .filter(h -> h.getArtist() != null && !h.getArtist().isEmpty())
                .collect(Collectors.groupingBy(PlayHistory::getArtist, Collectors.counting()));

        // 提取最常听的歌手（最多5个，增加多样性）
        List<String> topArtists = artistWeight.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 搜索歌手的歌，汇总去重，过滤已听
        Set<String> seenSourceIds = new HashSet<>(playedIds); // 直接从已听开始排除
        List<SongDTO> candidates = new ArrayList<>();

        for (String artist : topArtists) {
            try {
                List<SongDTO> songs = songService.search(artist, 1, 20, null); // 每位歌手搜20首
                for (SongDTO s : songs) {
                    if (s.getSourceId() != null && seenSourceIds.add(s.getSourceId())) {
                        if (s.getDuration() != null && s.getDuration() <= 30) continue;
                        candidates.add(s);
                    }
                }
            } catch (Exception e) {
                log.warn("搜索歌手 {} 失败", artist, e);
            }
        }

        // 先随机打乱，再按 RustFS 缓存优先排列（保证多样性 + 离线优先）
        Collections.shuffle(candidates);
        candidates.sort((a, b) -> {
            boolean aCached = checkCached(a.getSourceId());
            boolean bCached = checkCached(b.getSourceId());
            return Boolean.compare(bCached, aCached);
        });

        // 截取
        int take = Math.min(candidates.size(), RECOMMEND_COUNT);
        List<SongDTO> result = new ArrayList<>(candidates.subList(0, take));

        // 不够补充随机
        if (result.size() < RECOMMEND_COUNT) {
            try {
                List<SongDTO> supplement = songService.getRandomSongs(RECOMMEND_COUNT - result.size());
                for (SongDTO s : supplement) {
                    if (s.getSourceId() != null && seenSourceIds.add(s.getSourceId())) {
                        if (s.getDuration() != null && s.getDuration() <= 30) continue;
                        result.add(s);
                    }
                }
            } catch (Exception e) {
                log.warn("补充随机歌曲失败", e);
            }
        }

        // 再次随机打乱
        Collections.shuffle(result);

        // 标记离线状态
        markOfflineStatus(result);

        // 生成欢迎语
        String greeting = buildGreeting(history.size(), topArtists);

        return RecommendResult.builder()
                .songs(result)
                .greeting(greeting)
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
            List<SongDTO> songs = songService.getRandomSongs(RECOMMEND_COUNT);
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
     * 标记歌曲离线缓存状态，直接修改 SongDTO 的扩展字段
     */
    private void markOfflineStatus(List<SongDTO> songs) {
        for (SongDTO s : songs) {
            if (s.getSourceId() != null) {
                try {
                    s.setCached(storageService.exists("songs/" + s.getSourceId() + ".mp3"));
                } catch (Exception e) {
                    s.setCached(false);
                }
            }
        }
    }

    /**
     * 检查某首歌是否已缓存到 RustFS
     */
    private boolean checkCached(String sourceId) {
        if (sourceId == null) return false;
        try {
            return storageService.exists("songs/" + sourceId + ".mp3");
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
