package com.vibemusic.service;

import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    private final PlayHistoryMapper mapper;
    private static final int MAX_HISTORY = 300;
    private static final int CLEANUP_INTERVAL = 10; // 每 10 次播放触发 1 次清理
    private final AtomicInteger recordCounter = new AtomicInteger(0);

    @Transactional(rollbackFor = Exception.class)
    public void record(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        // 去重：检查最近一条记录是否同 sourceId，是则只更新时间
        PlayHistory last = mapper.selectOne(new LambdaQueryWrapper<PlayHistory>()
                .eq(PlayHistory::getUserId, userId)
                .orderByDesc(PlayHistory::getPlayedAt)
                .last("LIMIT 1"));
        if (last != null && last.getSourceId().equals(sourceId)) {
            last.setPlayedAt(LocalDateTime.now());
            mapper.updateById(last);
            return;
        }

        PlayHistory history = PlayHistory.builder()
                .userId(userId).sourceId(sourceId)
                .songName(songName).artist(artist).coverUrl(coverUrl).build();
        mapper.insert(history);

        // 概率性清理：每 CLEANUP_INTERVAL 次播放触发 1 次，减少 DELETE 开销
        if (recordCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            mapper.deleteOldByUserId(userId, MAX_HISTORY);
        }
    }

    public List<Map<String, Object>> recent(Long userId, int count) {
        count = Math.max(1, Math.min(count, MAX_HISTORY));
        List<PlayHistory> list = mapper.selectList(new LambdaQueryWrapper<PlayHistory>()
                .eq(PlayHistory::getUserId, userId)
                .orderByDesc(PlayHistory::getPlayedAt)
                .last("LIMIT " + count));
        Set<String> seen = new HashSet<>();
        return list.stream()
                .filter(h -> seen.add(h.getSourceId()))
                .map(h -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("sourceId", h.getSourceId());
                    m.put("songName", h.getSongName());
                    m.put("artist", h.getArtist());
                    String c = h.getCoverUrl();
                    m.put("coverUrl", c != null ? c.replace("http://", "https://") : "");
                    m.put("playedAt", h.getPlayedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }
}
