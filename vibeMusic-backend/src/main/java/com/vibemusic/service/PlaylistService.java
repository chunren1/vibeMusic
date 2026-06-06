package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.entity.Playlist;
import com.vibemusic.entity.PlaylistSong;
import com.vibemusic.mapper.PlaylistMapper;
import com.vibemusic.mapper.PlaylistSongMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistService {

    private final PlaylistMapper playlistMapper;
    private final PlaylistSongMapper songMapper;

    public List<Map<String, Object>> listPlaylists(Long userId) {
        List<Playlist> pls = playlistMapper.selectList(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getUserId, userId)
                .orderByDesc(Playlist::getCreatedAt));
        return pls.stream().map(pl -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", pl.getId());
            m.put("name", pl.getName());
            m.put("description", pl.getDescription());
            m.put("songCount", songMapper.selectCount(new LambdaQueryWrapper<PlaylistSong>()
                    .eq(PlaylistSong::getPlaylistId, pl.getId())));
            m.put("createdAt", pl.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long userId, String name, String description) {
        Playlist pl = Playlist.builder().userId(userId).name(name).description(description).build();
        playlistMapper.insert(pl);
        Map<String, Object> m = new HashMap<>();
        m.put("id", pl.getId());
        m.put("name", pl.getName());
        m.put("description", pl.getDescription());
        m.put("songCount", 0L);
        m.put("createdAt", pl.getCreatedAt());
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean addSong(Long userId, Long playlistId, String sourceId,
                           String songName, String artist, String coverUrl, Integer duration) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new RuntimeException("歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new RuntimeException("无权操作此歌单");
        if (songMapper.selectCount(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .eq(PlaylistSong::getSourceId, sourceId)) > 0) return false;
        PlaylistSong ps = PlaylistSong.builder()
                .playlistId(playlistId).sourceId(sourceId).songName(songName)
                .artist(artist).coverUrl(coverUrl).duration(duration).build();
        songMapper.insert(ps);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSong(Long userId, Long playlistId, String sourceId) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new RuntimeException("歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new RuntimeException("无权操作此歌单");
        songMapper.delete(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .eq(PlaylistSong::getSourceId, sourceId));
    }

    public List<Map<String, Object>> getSongs(Long playlistId) {
        List<PlaylistSong> list = songMapper.selectList(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .orderByDesc(PlaylistSong::getAddedAt));
        return list.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sourceId", s.getSourceId());
            m.put("songName", s.getSongName());
            m.put("artist", s.getArtist());
            m.put("coverUrl", s.getCoverUrl());
            m.put("duration", s.getDuration());
            m.put("addedAt", s.getAddedAt());
            return m;
        }).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long playlistId) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new RuntimeException("歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new RuntimeException("无权操作此歌单");
        songMapper.delete(new LambdaQueryWrapper<PlaylistSong>().eq(PlaylistSong::getPlaylistId, playlistId));
        playlistMapper.deleteById(playlistId);
    }
}
