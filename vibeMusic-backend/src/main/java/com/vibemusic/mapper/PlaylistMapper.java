package com.vibemusic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vibemusic.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {

    /**
     * 一次查询获取用户所有歌单及统计数据（歌曲数 + 最新封面）
     */
    @Select("""
        SELECT
            p.id          AS playlist_id,
            p.name        AS playlist_name,
            p.description AS description,
            p.created_at  AS created_at,
            COUNT(ps.id)  AS song_count,
            (SELECT ps2.cover_url FROM playlist_song ps2
             WHERE ps2.playlist_id = p.id
             ORDER BY ps2.added_at DESC LIMIT 1) AS cover_url
        FROM playlist p
        LEFT JOIN playlist_song ps ON ps.playlist_id = p.id
        WHERE p.user_id = #{userId}
        GROUP BY p.id
        ORDER BY p.created_at DESC
    """)
    List<Map<String, Object>> listPlaylistsWithStats(@Param("userId") Long userId);
}
