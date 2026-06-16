package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.Playlist;
import com.vibemusic.entity.PlaylistSong;
import com.vibemusic.mapper.PlaylistMapper;
import com.vibemusic.mapper.PlaylistSongMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 歌单 DTO（替代裸 Map，提供编译期类型安全）
 */
record PlaylistDTO(Long id, String name, String description, long songCount,
                   String coverUrl, Object createdAt) {
    static PlaylistDTO fromRow(Map<String, Object> row) {
        return new PlaylistDTO(
                toLong(row.get("playlist_id")),
                Objects.toString(row.get("playlist_name"), ""),
                Objects.toString(row.get("description"), ""),
                row.get("song_count") instanceof Number n ? n.longValue() : 0L,
                Objects.toString(row.get("cover_url"), ""),
                row.get("created_at")
        );
    }
    Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);           m.put("name", name);
        m.put("description", description); m.put("songCount", songCount);
        m.put("coverUrl", coverUrl);       m.put("createdAt", createdAt);
        return m;
    }
    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistService {

    private final PlaylistMapper playlistMapper;
    private final PlaylistSongMapper songMapper;

    public List<Map<String, Object>> listPlaylists(Long userId) {
        return playlistMapper.listPlaylistsWithStats(userId).stream()
                .map(PlaylistDTO::fromRow)
                .map(PlaylistDTO::toMap)
                .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long userId, String name, String description, String coverUrl) {
        Playlist pl = Playlist.builder().userId(userId).name(name).description(description).build();
        playlistMapper.insert(pl);
        Map<String, Object> m = new HashMap<>();
        m.put("id", pl.getId());
        m.put("name", pl.getName());
        m.put("description", pl.getDescription());
        m.put("coverUrl", coverUrl != null ? coverUrl : "");
        m.put("songCount", 0L);
        m.put("createdAt", pl.getCreatedAt());
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean addSong(Long userId, Long playlistId, String sourceId,
                           String songName, String artist, String coverUrl, Integer duration) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
        PlaylistSong ps = PlaylistSong.builder()
                .playlistId(playlistId).sourceId(sourceId).songName(songName)
                .artist(artist).coverUrl(coverUrl).duration(duration).build();
        try {
            songMapper.insert(ps);
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 唯一索引兜底：同一歌单不重复添加
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeSong(Long userId, Long playlistId, String sourceId) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
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
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
        songMapper.delete(new LambdaQueryWrapper<PlaylistSong>().eq(PlaylistSong::getPlaylistId, playlistId));
        playlistMapper.deleteById(playlistId);
    }
}
