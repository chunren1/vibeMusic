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

    @Select("SELECT p.id AS playlist_id, p.name AS playlist_name, p.description AS description, " +
            "CAST(p.sort_order AS SIGNED INTEGER) AS sort_order, " +
            "p.created_at AS created_at, " +
            "COALESCE((SELECT COUNT(1) FROM playlist_song ps WHERE ps.playlist_id = p.id), 0) AS song_count, " +
            "COALESCE((SELECT REPLACE(ps2.cover_url, 'http://', 'https://') FROM playlist_song ps2 " +
            " WHERE ps2.playlist_id = p.id ORDER BY ps2.added_at DESC LIMIT 1), '') AS cover_url " +
            "FROM playlist p " +
            "WHERE p.user_id = #{userId} " +
            "ORDER BY p.sort_order ASC, p.created_at DESC")
    List<Map<String, Object>> listPlaylistsWithStats(@Param("userId") Long userId);
}
