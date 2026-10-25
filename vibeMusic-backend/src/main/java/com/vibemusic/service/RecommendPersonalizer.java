package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 已登录用户个性化推荐（从 RecommendService 抽出，行为不变）。
 *
 * <p>v3 算法：随机打底 → 常听歌手并行 fan-out → 随机补足 → 忽略 seen 回填 → 打乱；
 * 候选池耗尽/上游异常一律降级为随机兜底。数量、deadline、采样窗口原样保留。
 */
@Slf4j
final class RecommendPersonalizer {

    private static final int HISTORY_DAYS = 30;
    private static final int HISTORY_SAMPLE = 100;
    private static final long ARTIST_FANOUT_DEADLINE_MS = 5000; // Top歌手并行搜索总deadline
    private static final int TOP_ARTIST_LIMIT = 5;

    private final PlayHistoryMapper playHistoryMapper;
    private final SongSearchService songSearchService;
    private final ThreadPoolTaskExecutor searchExecutor;
    private final RecommendCacheStore cacheStore;
    private final RecommendGuestBuilder guestBuilder;

    RecommendPersonalizer(PlayHistoryMapper playHistoryMapper, SongSearchService songSearchService,
                          ThreadPoolTaskExecutor searchExecutor, RecommendCacheStore cacheStore,
                          RecommendGuestBuilder guestBuilder) {
        this.playHistoryMapper = playHistoryMapper;
        this.songSearchService = songSearchService;
        this.searchExecutor = searchExecutor;
        this.cacheStore = cacheStore;
        this.guestBuilder = guestBuilder;
    }

    /**
     * 已登录用户个性化推荐（v3: 随机打底 + 兴趣扩展，保证多样性）
     * @param seenKey 已推荐记忆键（可为 null，Redis 异常时降级为空集）
     */
    RecommendResult buildPersonalized(Long userId, String seenKey) {
        // 获取近30天播放记录
        LocalDateTime since = LocalDateTime.now().minusDays(HISTORY_DAYS);
        List<PlayHistory> history = playHistoryMapper.selectPage(new Page<>(1, HISTORY_SAMPLE),
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId)
                        .ge(PlayHistory::getPlayedAt, since)
                        .orderByDesc(PlayHistory::getPlayedAt)).getRecords();

        // 一次遍历：收集已听 sourceId + 歌手权重
        Set<String> playedIds = new HashSet<>();
        Map<String, Long> artistWeight = new HashMap<>();
        for (PlayHistory h : history) {
            if (h.getSourceId() != null) playedIds.add(h.getSourceId());
            if (h.getArtist() != null && !h.getArtist().isEmpty()) {
                artistWeight.merge(h.getArtist(), 1L, Long::sum);
            }
        }
        // 已推荐记忆 ∪ 已播放 = 本次去重集合。连续"换一批"(refresh=true)时过滤近期已推过的歌。
        Set<String> seenIds = cacheStore.readSeenIds(seenKey);
        Set<String> seenSourceIds = new HashSet<>(playedIds);
        seenSourceIds.addAll(seenIds);

        List<SongDTO> result = new ArrayList<>();
        Map<String, Integer> artistCount = new HashMap<>();
        // seen 命中的溢出缓冲：候选池耗尽时忽略 seen 回填，保证数量不比原来少
        List<SongDTO> seenSkipped = new ArrayList<>();
        Set<String> skippedIds = new HashSet<>();

        // ① 随机打底：保证多样性
        try {
            List<SongDTO> random = songSearchService.getRandomSongs(RecommendGuestBuilder.RANDOM_BASE);
            for (SongDTO s : random) {
                if (s.getSourceId() == null) continue;
                if (playedIds.contains(s.getSourceId())) continue;
                if (seenIds.contains(s.getSourceId())) {
                    if (skippedIds.add(s.getSourceId())) seenSkipped.add(s);
                    continue;
                }
                if (!seenSourceIds.add(s.getSourceId())) continue;
                if (s.getDuration() == null || s.getDuration() > 30) {
                    if (RecommendRules.tryCountArtist(artistCount, s.getArtist())) {
                        result.add(s);
                    }
                }
            }
        } catch (Exception e) { log.warn("随机推荐获取失败", e); }

        // ② 兴趣扩展：基于常听歌手搜歌（并行 fan-out + 总 deadline，凑满即取消余下）
        if (result.size() < RecommendService.RECOMMEND_COUNT && !history.isEmpty()) {
            List<String> topArtists = artistWeight.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(TOP_ARTIST_LIMIT)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            searchTopArtistsParallel(topArtists, result, seenSourceIds, playedIds, seenIds,
                    artistCount, seenSkipped, skippedIds);
        }

        // ③ 仍有空缺 → 补随机
        if (result.size() < RecommendService.RECOMMEND_COUNT) {
            try {
                List<SongDTO> supplement =
                        songSearchService.getRandomSongs(RecommendService.RECOMMEND_COUNT - result.size());
                for (SongDTO s : supplement) {
                    if (result.size() >= RecommendService.RECOMMEND_COUNT) break;
                    if (s.getSourceId() == null) continue;
                    if (playedIds.contains(s.getSourceId())) continue;
                    if (seenIds.contains(s.getSourceId())) {
                        if (skippedIds.add(s.getSourceId())) seenSkipped.add(s);
                        continue;
                    }
                    if (!seenSourceIds.add(s.getSourceId())) continue;
                    if (s.getDuration() != null && s.getDuration() <= 30) continue;
                    if (!RecommendRules.tryCountArtist(artistCount, s.getArtist())) continue;
                    result.add(s);
                }
            } catch (Exception e) { log.warn("补充随机歌曲失败", e); }
        }

        // ③′ 候选池耗尽回填：忽略 seen（仅保留 played 去重 + 时长 + 每歌手上限），把数量补满 8
        if (result.size() < RecommendService.RECOMMEND_COUNT && !seenSkipped.isEmpty()) {
            Set<String> resultIds = new HashSet<>(playedIds);
            for (SongDTO s : result) {
                if (s.getSourceId() != null) resultIds.add(s.getSourceId());
            }
            for (SongDTO s : seenSkipped) {
                if (result.size() >= RecommendService.RECOMMEND_COUNT) break;
                if (s.getSourceId() == null || !resultIds.add(s.getSourceId())) continue;
                if (s.getDuration() != null && s.getDuration() <= 30) continue;
                if (!RecommendRules.tryCountArtist(artistCount, s.getArtist())) continue;
                result.add(s);
            }
        }

        // 写回已推荐记忆（best-effort，Redis 失败不影响本次结果）
        cacheStore.writeSeenIds(seenKey, result);

        // ④ 多样性加权随机打乱 + 多样性保障：至少保留 1 首非 TopArtist 的歌
        if (result.size() >= 3) {
            // 不把所有来自同一个TopArtist的歌挨在一起
            Collections.shuffle(result, new Random(System.nanoTime()));
        } else {
            Collections.shuffle(result);
        }

        // 仍无结果 → 全随机兜底
        if (result.isEmpty()) {
            return guestBuilder.buildGuestResult("最近没有听过歌？试试这些吧~");
        }

        guestBuilder.markOfflineStatus(result);

        String greeting = guestBuilder.buildGreeting(history.size(), history.stream()
                .filter(h -> h.getArtist() != null)
                .collect(Collectors.groupingBy(PlayHistory::getArtist, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList()));

        return RecommendResult.builder()
                .songs(result)
                .greeting(greeting)
                .reason(guestBuilder.buildReason(history))
                .type("personalized")
                .build();
    }

    /**
     * Top 歌手并行搜索：复用 searchExecutor fan-out，按提交顺序在总 deadline 内取结果；
     * 凑满配额或超时即取消余下分支。单分支异常/超时/线程池过载只跳过该分支，
     * 后续随机补足兜底保证数量。
     */
    private void searchTopArtistsParallel(List<String> topArtists, List<SongDTO> result,
                                           Set<String> seenSourceIds, Set<String> playedIds,
                                           Set<String> seenIds, Map<String, Integer> artistCount,
                                           List<SongDTO> seenSkipped, Set<String> skippedIds) {
        List<Future<List<SongDTO>>> futures = new ArrayList<>(topArtists.size());
        try {
            for (String artist : topArtists) {
                futures.add(searchExecutor.submit(() -> {
                    try {
                        return songSearchService.search(artist, 1, 10, null).getList();
                    } catch (Exception e) {
                        log.warn("搜索歌手 {} 失败", artist, e);
                        return Collections.emptyList();
                    }
                }));
            }
        } catch (RejectedExecutionException rex) {
            log.warn("推荐搜索线程池过载，跳过兴趣扩展", rex);
            for (Future<List<SongDTO>> f : futures) f.cancel(true);
            return;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ARTIST_FANOUT_DEADLINE_MS);
        for (Future<List<SongDTO>> f : futures) {
            if (result.size() >= RecommendService.RECOMMEND_COUNT) break;
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                log.warn("推荐歌手搜索触及总 deadline({}ms)，停止等待余下分支", ARTIST_FANOUT_DEADLINE_MS);
                break;
            }
            List<SongDTO> songs;
            try {
                songs = f.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException te) {
                log.warn("推荐歌手搜索触及总 deadline({}ms)，停止等待余下分支", ARTIST_FANOUT_DEADLINE_MS);
                break;
            } catch (Exception e) {
                log.warn("推荐歌手搜索分支异常，跳过", e);
                continue;
            }
            if (songs == null) continue;
            for (SongDTO s : songs) {
                if (result.size() >= RecommendService.RECOMMEND_COUNT) break;
                if (s.getSourceId() == null) continue;
                if (playedIds.contains(s.getSourceId())) continue;
                if (seenIds.contains(s.getSourceId())) {
                    if (skippedIds.add(s.getSourceId())) seenSkipped.add(s);
                    continue;
                }
                if (!seenSourceIds.add(s.getSourceId())) continue;
                if (s.getDuration() != null && s.getDuration() <= 30) continue;
                if (!RecommendRules.tryCountArtist(artistCount, s.getArtist())) continue;
                result.add(s);
            }
        }
        for (Future<List<SongDTO>> f : futures) {
            if (!f.isDone()) f.cancel(true);
        }
    }
}
