package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.PlaylistSong;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlaylistSongMapper extends BaseMapper<PlaylistSong> {

    /**
     * 批量插入歌单歌曲（一次 SQL 插入整批，替代逐条 insert）
     * SQL 定义在: resources/mapper/PlaylistSongMapper.xml
     */
    int insertBatch(@Param("list") List<PlaylistSong> songs);
}
