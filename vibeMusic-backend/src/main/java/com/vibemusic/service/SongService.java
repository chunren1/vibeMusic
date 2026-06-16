package com.vibemusic.service;

import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 歌曲数据持久化服务
 * <p>
 * 职责：歌曲入库/查询（search 已迁移至 SongSearchService，play 已迁移至 SongPlayService）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongService {

    private final SongMapper songMapper;

    public Song saveDownloadedSong(String sourceId, String name, String artist,
                                    String album, String coverUrl, Integer duration, String rustfsUrl) {
        // 一次 SQL 完成：INSERT ... ON DUPLICATE KEY UPDATE（避免 1 次 SELECT + 1 次 INSERT/UPDATE）
        Song song = Song.builder()
                .sourceId(sourceId).name(name).artist(artist)
                .album(album).coverUrl(coverUrl).duration(duration).url(rustfsUrl)
                .build();
        songMapper.insertOrUpdateUrl(song);
        // 入库后查回完整对象（含自增 id、时间戳等）
        return songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
    }

    public Song getById(Long id) { return songMapper.selectById(id); }

    public Song getBySourceId(String sourceId) {
        return songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
    }
}

