package com.vibemusic.service;

import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    private final PlayHistoryMapper mapper;
    private static final int MAX_HISTORY = 300;

    @Transactional(rollbackFor = Exception.class)
    public void record(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        PlayHistory history = PlayHistory.builder()
                .userId(userId).sourceId(sourceId)
                .songName(songName).artist(artist).coverUrl(coverUrl).build();
        mapper.insert(history);

        // 保留最近 MAX_HISTORY 条，批量删除超出的
        List<PlayHistory> list = mapper.selectList(new LambdaQueryWrapper<PlayHistory>()
                .eq(PlayHistory::getUserId, userId)
                .orderByDesc(PlayHistory::getPlayedAt)
                .last("LIMIT " + (MAX_HISTORY + 50))); // 只取需要判断的条数
        if (list.size() > MAX_HISTORY) {
            List<Long> toDelete = list.subList(MAX_HISTORY, list.size()).stream()
                    .map(PlayHistory::getId).collect(Collectors.toList());
            if (!toDelete.isEmpty()) {
                mapper.deleteBatchIds(toDelete);
            }
        }
    }

    public List<Map<String, Object>> recent(Long userId, int count) {
        if (count > MAX_HISTORY) count = MAX_HISTORY;
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
                    m.put("coverUrl", h.getCoverUrl());
                    m.put("playedAt", h.getPlayedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }
}
