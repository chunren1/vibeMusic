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

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 歌单 DTO（替代裸 Map，提供编译期类型安全）
 */
record PlaylistDTO(Long id, String name, String description, long songCount,
                   String coverUrl, Integer sortOrder, Object createdAt) {
    static PlaylistDTO fromRow(Map<String, Object> row) {
        return new PlaylistDTO(
                toLong(row.get("playlist_id")),
                Objects.toString(row.get("playlist_name"), ""),
                Objects.toString(row.get("description"), ""),
                row.get("song_count") instanceof Number n ? n.longValue() : 0L,
                Objects.toString(row.get("cover_url"), ""),
                row.get("sort_order") instanceof Number n ? n.intValue() : 0,
                row.get("created_at")
        );
    }
    Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);           m.put("name", name);
        m.put("description", description); m.put("songCount", songCount);
        m.put("coverUrl", coverUrl);       m.put("sortOrder", sortOrder);
        m.put("createdAt", createdAt);
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
        // 去重：同名歌单已存在则直接返回已有歌单
        Playlist existing = playlistMapper.selectOne(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getUserId, userId)
                .eq(Playlist::getName, name));
        if (existing != null) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", existing.getId());
            m.put("name", existing.getName());
            m.put("description", existing.getDescription());
            m.put("coverUrl", coverUrl != null ? coverUrl : "");
            m.put("songCount", songMapper.selectCount(new LambdaQueryWrapper<PlaylistSong>()
                    .eq(PlaylistSong::getPlaylistId, existing.getId())));
            m.put("createdAt", existing.getCreatedAt());
            m.put("duplicate", true);
            return m;
        }
        Playlist pl = Playlist.builder().userId(userId).name(name)
                .description(description).coverUrl(coverUrl != null ? coverUrl : "").build();
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
        return addSong(userId, playlistId, sourceId, songName, artist, coverUrl, duration, "netease");
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean addSong(Long userId, Long playlistId, String sourceId,
                           String songName, String artist, String coverUrl, Integer duration,
                           String platform) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");

        // 去重预检：已存在则直接返回 false，避免抛异常开销
        boolean exists = songMapper.exists(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .eq(PlaylistSong::getSourceId, sourceId));
        if (exists) return false;

        String resolvedPlatform = (platform == null || platform.isBlank()) ? "netease" : platform.trim();
        PlaylistSong ps = PlaylistSong.builder()
                .playlistId(playlistId).sourceId(sourceId).songName(songName)
                .artist(artist).coverUrl(coverUrl).duration(duration)
                .platform(resolvedPlatform).build();
        try {
            songMapper.insert(ps);
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 唯一索引兜底：并发场景下仍按约束拒绝
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long playlistId, String name, String description, String coverUrl) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
        if (name != null && !name.isBlank()) pl.setName(name.trim());
        if (description != null) pl.setDescription(description);
        if (coverUrl != null) pl.setCoverUrl(coverUrl);
        playlistMapper.updateById(pl);
        log.info("歌单更新: id={}, name={}", playlistId, pl.getName());
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorder(Long userId, List<Map<String, Object>> order) {
        for (Map<String, Object> item : order) {
            Long id = item.get("playlistId") instanceof Number n ? n.longValue() : null;
            int sort = item.get("sortOrder") instanceof Number n ? n.intValue() : 0;
            if (id == null) continue;
            // 直接用 UpdateWrapper SET，绕过 MyBatis-Plus 字段策略
            playlistMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Playlist>()
                    .eq(Playlist::getId, id)
                    .eq(Playlist::getUserId, userId)
                    .set(Playlist::getSortOrder, sort));
        }
        log.info("歌单排序更新: userId={}, 歌单数={}", userId, order.size());
    }

    /** 导出歌单为 JSON 文本 */
    public Map<String, Object> export(Long userId, Long playlistId) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
        List<PlaylistSong> songs = songMapper.selectList(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .orderByDesc(PlaylistSong::getAddedAt));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", pl.getName());
        data.put("description", pl.getDescription());
        data.put("exportTime", java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        data.put("songCount", songs.size());
        List<Map<String, Object>> songList = songs.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("songName", s.getSongName());
            m.put("artist", s.getArtist());
            m.put("sourceId", s.getSourceId());
            m.put("coverUrl", s.getCoverUrl());
            m.put("duration", s.getDuration());
            return m;
        }).collect(Collectors.toList());
        data.put("songs", songList);
        return data;
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

    public List<Map<String, Object>> getSongs(Long userId, Long playlistId) {
        Playlist pl = playlistMapper.selectById(playlistId);
        if (pl == null) throw new BusinessException(404, "歌单不存在");
        if (!pl.getUserId().equals(userId)) throw new BusinessException(403, "无权操作此歌单");
        List<PlaylistSong> list = songMapper.selectList(new LambdaQueryWrapper<PlaylistSong>()
                .eq(PlaylistSong::getPlaylistId, playlistId)
                .orderByDesc(PlaylistSong::getAddedAt));
        return list.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sourceId", s.getSourceId());
            // 原生 App 用 name + platform；Web 端继续用 songName（双写兼容）
            m.put("name", s.getSongName());
            m.put("songName", s.getSongName());
            m.put("platform", s.getPlatform() != null && !s.getPlatform().isBlank() ? s.getPlatform() : "netease");
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

    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(Long userId, List<Long> playlistIds) {
        // 先验证所有权
        List<Playlist> owned = playlistMapper.selectBatchIds(playlistIds);
        List<Long> validIds = owned.stream()
                .filter(pl -> pl.getUserId().equals(userId))
                .map(Playlist::getId)
                .collect(Collectors.toList());
        if (validIds.isEmpty()) return 0;
        // 批量删除歌曲 + 批量删除歌单（2 次 SQL 替代 N×2 次）
        songMapper.delete(new LambdaQueryWrapper<PlaylistSong>()
                .in(PlaylistSong::getPlaylistId, validIds));
        playlistMapper.deleteBatchIds(validIds);
        return validIds.size();
    }

    /**
     * 导入外部歌单：创建歌单 + 批量添加歌曲
     * @return 导入的歌曲数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int importPlaylist(Long userId, String name, String coverUrl,
                              List<Map<String, Object>> songs) {
        return importPlaylist(userId, name, coverUrl, songs, "netease");
    }

    /**
     * 导入外部歌单（携带来源平台）：创建歌单 + 批量添加歌曲
     * @param source 外部来源 netease/qq/migu，未知或空值回落 netease（兼容旧调用）
     * @return 导入的歌曲数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int importPlaylist(Long userId, String name, String coverUrl,
                              List<Map<String, Object>> songs, String source) {
        // 去重：同名歌单已存在则复用，只追加新歌
        Playlist existing = playlistMapper.selectOne(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getUserId, userId)
                .eq(Playlist::getName, name));
        Playlist pl;
        boolean reuse = existing != null;
        if (reuse) {
            pl = existing;
        } else {
            // 1. 创建歌单
            pl = Playlist.builder().userId(userId).name(name)
                    .description("从推荐歌单导入").build();
            playlistMapper.insert(pl);
        }
        // 2. 批量添加歌曲（先批量查询存在的 sourceId，再分批插入）
        int added = 0;
        if (songs.isEmpty()) return added;

        // 2.1 批量查询已存在的 sourceId（1 次 SQL 替代 N 次 DuplicateKeyException）
        List<String> sourceIds = songs.stream()
                .map(s -> String.valueOf(s.get("id")))
                .distinct().collect(Collectors.toList());
        Set<String> existingIds = new HashSet<>(songMapper.selectList(
                new LambdaQueryWrapper<PlaylistSong>()
                        .eq(PlaylistSong::getPlaylistId, pl.getId())
                        .in(PlaylistSong::getSourceId, sourceIds))
                .stream().map(PlaylistSong::getSourceId).collect(Collectors.toSet()));

        // 2.2 过滤出新歌，分批插入（每批 50，防止大事务锁表）
        String resolvedPlatform = normalizePlatform(source);
        List<PlaylistSong> toInsert = songs.stream()
                .filter(s -> !existingIds.contains(String.valueOf(s.get("id"))))
                .map(s -> PlaylistSong.builder()
                        .playlistId(pl.getId())
                        .sourceId(String.valueOf(s.get("id")))
                        .songName(String.valueOf(s.getOrDefault("name", "")))
                        .artist(String.valueOf(s.getOrDefault("artist", "")))
                        .coverUrl(String.valueOf(s.getOrDefault("coverUrl", "")))
                        .duration(s.get("duration") instanceof Number n ? n.intValue() : 0)
                        .platform(resolvedPlatform)
                        .build())
                .collect(Collectors.toList());

        int batchSize = 50;
        for (int i = 0; i < toInsert.size(); i += batchSize) {
            List<PlaylistSong> batch = toInsert.subList(i, Math.min(i + batchSize, toInsert.size()));
            // 一次 SQL 插入整批；INSERT IGNORE 兜底并发唯一索引冲突（跳过重复行）
            added += songMapper.insertBatch(batch);
        }
        log.info("用户 {} 导入歌单 [{}] ({} 首歌曲)", userId, name, added);
        return added;
    }

    /** 归一化平台标识：仅接受 netease/qq/migu/kugou/bilibili，其余回落 netease（与 DB 默认一致） */
    private static String normalizePlatform(String source) {
        if (source == null) return "netease";
        String s = source.trim().toLowerCase();
        return ("netease".equals(s) || "qq".equals(s) || "migu".equals(s) || "kugou".equals(s) || "bilibili".equals(s)) ? s : "netease";
    }
}
