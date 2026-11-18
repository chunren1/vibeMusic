package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.PlayHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {

    /**
     * 删除旧记录，只保留最近 N 条
     * SQL 定义在: resources/mapper/PlayHistoryMapper.xml
     */
    int deleteOldByUserId(@Param("userId") Long userId, @Param("keep") int keep);

    /**
     * 近 N 天播放次数最多的曲目（热歌缓存预热用）。
     * SQL 定义在: resources/mapper/PlayHistoryMapper.xml
     */
    List<Map<String, Object>> selectHotSongs(@Param("days") int days, @Param("limit") int limit);
}
