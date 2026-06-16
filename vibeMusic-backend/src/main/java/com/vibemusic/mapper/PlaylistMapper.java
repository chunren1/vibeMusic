package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {

    /**
     * 一次查询获取用户所有歌单及统计数据（歌曲数 + 最新封面）
     * SQL 定义在: resources/mapper/PlaylistMapper.xml
     */
    List<Map<String, Object>> listPlaylistsWithStats(@Param("userId") Long userId);
}
