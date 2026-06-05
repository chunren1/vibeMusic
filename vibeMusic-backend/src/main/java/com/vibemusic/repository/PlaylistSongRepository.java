package com.vibemusic.repository;

import com.vibemusic.entity.PlaylistSong;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlaylistSongRepository extends JpaRepository<PlaylistSong, Long> {
    List<PlaylistSong> findByPlaylistIdOrderByAddedAtDesc(Long playlistId);
    Optional<PlaylistSong> findByPlaylistIdAndSourceId(Long playlistId, String sourceId);
    long countByPlaylistId(Long playlistId);
    void deleteByPlaylistIdAndSourceId(Long playlistId, String sourceId);
}
