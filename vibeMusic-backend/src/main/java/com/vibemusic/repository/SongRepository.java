package com.vibemusic.repository;

import com.vibemusic.entity.Song;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 歌曲表（仅存储已下载到 RustFS 的歌曲）
 */
@Repository
public interface SongRepository extends JpaRepository<Song, Long> {

    /**
     * 根据网易云 sourceId 查找
     */
    Song findBySourceId(String sourceId);

    /**
     * 随机获取 N 首（首页推荐用，从已下载歌曲中随机）
     */
    @Query(value = "SELECT * FROM song ORDER BY RAND() LIMIT :count", nativeQuery = true)
    List<Song> findRandomSongs(int count);
}
