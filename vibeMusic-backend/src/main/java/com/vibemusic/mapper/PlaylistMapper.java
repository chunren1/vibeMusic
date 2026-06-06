package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {
}
