package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.PlayHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlayHistoryMapper extends BaseMapper<PlayHistory> {

    /**
     * 删除旧记录，只保留最近 N 条
     * SQL 定义在: resources/mapper/PlayHistoryMapper.xml
     */
    int deleteOldByUserId(@Param("userId") Long userId, @Param("keep") int keep);
}
