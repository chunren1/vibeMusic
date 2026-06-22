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

    private static final String[][] DEFAULT_PLAYLISTS = {
        {"华语热门精选", "华语乐坛经典与热门歌曲"},
        {"治愈系纯音乐", "放松心情的优美纯音乐"},
        {"说唱新世代", "中文说唱的无限可能"},
        {"怀旧金曲", "那些年我们追过的经典"},
        {"民谣在路上", "吉他声里的故事与远方"},
        {"电竞燃曲BGM", "高燃BGM助你上分"},
    };

    /** 为新用户创建默认歌单 */
    public void seedDefaults(Long userId) {
        for (String[] pl : DEFAULT_PLAYLISTS) {
            create(userId, pl[0], pl[1], null);
        }
        log.info("为新用户 {} 创建了 {} 个默认歌单", userId, DEFAULT_PLAYLISTS.length);
    }

    public List<Map<String, Object>> listPlaylists(Long userId) {
        List<Map<String, Object>> rows = playlistMapper.listPlaylistsWithStats(userId);
        log.info("查询歌单列表: userId={}, 原始行数={}", userId, rows.size());
        if (!rows.isEmpty()) {
            log.info("首条数据示例: {}", rows.get(0));
        }
        return rows.stream()
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
            // 升级 HTTP → HTTPS，防止手机通过 HTTPS 隧道时混合内容被浏览器拦截
            String cover = s.getCoverUrl();
            m.put("coverUrl", cover != null ? cover.replace("http://", "https://") : "");
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

    /**
     * 导入外部歌单：创建歌单 + 批量添加歌曲
     * @return 导入的歌曲数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int importPlaylist(Long userId, String name, String coverUrl,
                              List<Map<String, Object>> songs) {
        // 1. 创建歌单
        Playlist pl = Playlist.builder().userId(userId).name(name)
                .description("从推荐歌单导入").build();
        playlistMapper.insert(pl);
        // 2. 批量添加歌曲（跳过重复）
        int added = 0;
        for (Map<String, Object> s : songs) {
            try {
                PlaylistSong ps = PlaylistSong.builder()
                        .playlistId(pl.getId())
                        .sourceId(String.valueOf(s.get("id")))
                        .songName(String.valueOf(s.getOrDefault("name", "")))
                        .artist(String.valueOf(s.getOrDefault("artist", "")))
                        .coverUrl(String.valueOf(s.getOrDefault("coverUrl", "")))
                        .duration(s.get("duration") instanceof Number n ? n.intValue() : 0)
                        .build();
                songMapper.insert(ps);
                added++;
            } catch (DuplicateKeyException ignored) {
                // 唯一索引兜底，跳过重复歌曲
            }
        }
        log.info("用户 {} 导入歌单 [{}] ({} 首歌曲)", userId, name, added);
        return added;
    }
}
