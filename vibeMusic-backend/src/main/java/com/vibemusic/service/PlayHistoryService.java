package com.vibemusic.service;

import com.vibemusic.entity.PlayHistory;
import com.vibemusic.repository.PlayHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryService {

    private final PlayHistoryRepository repository;

    /**
     * 记录播放
     */
    public void record(Long userId, String sourceId, String songName, String artist) {
        PlayHistory history = PlayHistory.builder()
                .userId(userId)
                .sourceId(sourceId)
                .songName(songName)
                .artist(artist)
                .build();
        repository.save(history);
        log.debug("记录播放历史: user={}, song={}", userId, songName);
    }

    /**
     * 最近播放列表（去重，同一首歌只保留最新记录）
     */
    public List<Map<String, Object>> recent(Long userId, int count) {
        List<PlayHistory> list = repository.findRecentByUserId(userId, count);

        // 去重 + 转为 DTO
        Set<String> seen = new HashSet<>();
        return list.stream()
                .filter(h -> seen.add(h.getSourceId()))
                .map(h -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("sourceId", h.getSourceId());
                    m.put("songName", h.getSongName());
                    m.put("artist", h.getArtist());
                    m.put("playedAt", h.getPlayedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }
}
