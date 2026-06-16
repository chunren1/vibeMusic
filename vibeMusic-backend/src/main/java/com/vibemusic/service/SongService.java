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
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
        if (song == null) {
            song = Song.builder().sourceId(sourceId).name(name).artist(artist)
                    .album(album).coverUrl(coverUrl).duration(duration).url(rustfsUrl).build();
            songMapper.insert(song);
        } else {
            song.setUrl(rustfsUrl);
            songMapper.updateById(song);
        }
        return song;
    }

    public Song getById(Long id) { return songMapper.selectById(id); }

    public Song getBySourceId(String sourceId) {
        return songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
    }
}

