package com.vibemusic.repository;

import com.vibemusic.entity.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    Optional<UserFavorite> findByUserIdAndSourceId(Long userId, String sourceId);

    boolean existsByUserIdAndSourceId(Long userId, String sourceId);

    @Query(value = "SELECT * FROM user_favorite WHERE user_id = :userId ORDER BY created_at DESC LIMIT :count", nativeQuery = true)
    List<UserFavorite> findByUserId(Long userId, int count);

    int countByUserId(Long userId);
}
