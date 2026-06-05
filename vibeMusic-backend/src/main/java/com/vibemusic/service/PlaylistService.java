package com.vibemusic.service;

import com.vibemusic.entity.Playlist;
import com.vibemusic.entity.PlaylistSong;
import com.vibemusic.repository.PlaylistRepository;
import com.vibemusic.repository.PlaylistSongRepository;
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

    private final PlaylistRepository playlistRepo;
    private final PlaylistSongRepository songRepo;

    /** 获取用户的所有歌单（带歌曲数） */
    public List<Map<String, Object>> listPlaylists(Long userId) {
        List<Playlist> pls = playlistRepo.findByUserIdOrderByCreatedAtDesc(userId);
        return pls.stream().map(pl -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", pl.getId());
            m.put("name", pl.getName());
            m.put("description", pl.getDescription());
            m.put("songCount", songRepo.countByPlaylistId(pl.getId()));
            m.put("createdAt", pl.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /** 创建歌单 */
    @Transactional
    public Map<String, Object> create(Long userId, String name, String description) {
        Playlist pl = Playlist.builder()
                .userId(userId).name(name).description(description).build();
        pl = playlistRepo.save(pl);
        Map<String, Object> m = new HashMap<>();
        m.put("id", pl.getId());
        m.put("name", pl.getName());
        m.put("description", pl.getDescription());
        m.put("songCount", 0L);
        m.put("createdAt", pl.getCreatedAt());
        return m;
    }

    /** 添加歌曲到歌单 */
    @Transactional
    public boolean addSong(Long userId, Long playlistId, String sourceId,
                           String songName, String artist, String coverUrl, Integer duration) {
        Playlist pl = playlistRepo.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("歌单不存在"));
        if (!pl.getUserId().equals(userId))
            throw new RuntimeException("无权操作此歌单");

        if (songRepo.findByPlaylistIdAndSourceId(playlistId, sourceId).isPresent())
            return false; // 已存在

        PlaylistSong ps = PlaylistSong.builder()
                .playlistId(playlistId).sourceId(sourceId)
                .songName(songName).artist(artist)
                .coverUrl(coverUrl).duration(duration).build();
        songRepo.save(ps);
        log.info("添加到歌单: playlist={}, song={}", playlistId, songName);
        return true;
    }

    /** 从歌单移除歌曲 */
    @Transactional
    public void removeSong(Long userId, Long playlistId, String sourceId) {
        Playlist pl = playlistRepo.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("歌单不存在"));
        if (!pl.getUserId().equals(userId))
            throw new RuntimeException("无权操作此歌单");
        songRepo.deleteByPlaylistIdAndSourceId(playlistId, sourceId);
    }

    /** 获取歌单中的歌曲 */
    public List<Map<String, Object>> getSongs(Long playlistId) {
        List<PlaylistSong> list = songRepo.findByPlaylistIdOrderByAddedAtDesc(playlistId);
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

    /** 删除歌单 */
    @Transactional
    public void delete(Long userId, Long playlistId) {
        Playlist pl = playlistRepo.findById(playlistId)
                .orElseThrow(() -> new RuntimeException("歌单不存在"));
        if (!pl.getUserId().equals(userId))
            throw new RuntimeException("无权操作此歌单");
        // 先删关联歌曲
        songRepo.findByPlaylistIdOrderByAddedAtDesc(playlistId)
                .forEach(s -> songRepo.delete(s));
        playlistRepo.delete(pl);
    }
}
