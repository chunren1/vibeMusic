package com.vibemusic.service;

import com.vibemusic.entity.PlayHistory;
import com.vibemusic.repository.PlayHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    private final PlayHistoryRepository repository;
    private static final int MAX_HISTORY = 300;

    @Transactional
    public void record(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        PlayHistory history = PlayHistory.builder()
                .userId(userId).sourceId(sourceId)
                .songName(songName).artist(artist).coverUrl(coverUrl).build();
        repository.save(history);
        int total = repository.countByUserId(userId);
        if (total > MAX_HISTORY) {
            int deleted = repository.deleteOldByUserId(userId, MAX_HISTORY);
            log.debug("deleted {} old history records for user {}", deleted, userId);
        }
    }

    public List<Map<String, Object>> recent(Long userId, int count) {
        if (count > MAX_HISTORY) count = MAX_HISTORY;
        List<PlayHistory> list = repository.findRecentByUserId(userId, count);
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
                .collect(java.util.stream.Collectors.toList());
    }
}