package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.Song;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SongMapper extends BaseMapper<Song> {

    /**
     * 随机获取 N 首歌曲
     * SQL 定义在: resources/mapper/SongMapper.xml
     */
    List<Song> findRandomSongs(@Param("count") int count);
}
