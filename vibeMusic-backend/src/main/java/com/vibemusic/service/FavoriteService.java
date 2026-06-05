package com.vibemusic.service;

import com.vibemusic.entity.UserFavorite;
import com.vibemusic.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteRepository repository;

    /** 切换收藏状态，返回 true=已收藏 false=已取消 */
    @Transactional
    public boolean toggle(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        Optional<UserFavorite> existing = repository.findByUserIdAndSourceId(userId, sourceId);
        if (existing.isPresent()) {
            repository.delete(existing.get());
            log.info("取消收藏: user={}, song={}", userId, songName);
            return false;
        }
        UserFavorite fav = UserFavorite.builder()
                .userId(userId)
                .sourceId(sourceId)
                .songName(songName)
                .artist(artist)
                .coverUrl(coverUrl)
                .build();
        repository.save(fav);
        log.info("收藏: user={}, song={}", userId, songName);
        return true;
    }

    /** 获取收藏列表 */
    public List<Map<String, Object>> list(Long userId, int count) {
        List<UserFavorite> list = repository.findByUserId(userId, count);
        return list.stream().map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sourceId", f.getSourceId());
            m.put("songName", f.getSongName());
            m.put("artist", f.getArtist());
            m.put("coverUrl", f.getCoverUrl());
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /** 获取用户收藏的 sourceId 集合（用于前端高亮） */
    public Set<String> favoritesSet(Long userId) {
        return repository.findByUserId(userId, 999).stream()
                .map(UserFavorite::getSourceId)
                .collect(Collectors.toSet());
    }
}
