package com.vibemusic.repository;

import com.vibemusic.entity.PlayHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

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

    @Query(value = "SELECT COUNT(*) FROM play_history WHERE user_id = :userId", nativeQuery = true)
    int countByUserId(Long userId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM play_history WHERE user_id = :userId AND id NOT IN (SELECT t.id FROM (SELECT id FROM play_history WHERE user_id = :userId ORDER BY played_at DESC LIMIT :keep) AS t)", nativeQuery = true)
    int deleteOldByUserId(Long userId, int keep);
}
