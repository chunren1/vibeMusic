package com.vibemusic.repository;

import com.vibemusic.entity.PlayHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlayHistoryRepository extends JpaRepository<PlayHistory, Long> {

    @Query(value = """
        SELECT * FROM play_history
        WHERE user_id = :userId
        ORDER BY played_at DESC
        LIMIT :count
    """, nativeQuery = true)
    List<PlayHistory> findRecentByUserId(Long userId, int count);
}
