package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.entity.UserFavorite;
import com.vibemusic.mapper.UserFavoriteMapper;
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

    private final UserFavoriteMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        UserFavorite existing = mapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getSourceId, sourceId));
        if (existing != null) {
            mapper.deleteById(existing.getId());
            return false;
        }
        UserFavorite fav = UserFavorite.builder()
                .userId(userId).sourceId(sourceId).songName(songName)
                .artist(artist).coverUrl(coverUrl).build();
        mapper.insert(fav);
        return true;
    }

    public List<Map<String, Object>> list(Long userId, int count) {
        List<UserFavorite> list = mapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .orderByDesc(UserFavorite::getCreatedAt)
                .last("LIMIT " + count));
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

    public Set<String> favoritesSet(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .last("LIMIT 999")).stream()
                .map(UserFavorite::getSourceId)
                .collect(Collectors.toSet());
    }
}
