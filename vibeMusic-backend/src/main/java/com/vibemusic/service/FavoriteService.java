package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.entity.UserFavorite;
import com.vibemusic.mapper.UserFavoriteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final UserFavoriteMapper mapper;
    private static final int MAX_FAVORITES = 999;
    private static final int MAX_LIST = 500;

    /**
     * 切换收藏状态，自动重试最多 3 次以解决并发冲突
     * <p>
     * 高并发场景（50 VU）：SELECT 与 INSERT 之间存在竞态窗口，
     * 两个线程同时 SELECT 得到 null，然后同时 INSERT 导致唯一索引冲突。
     * 重试后 SELECT 能正确找到已插入的记录，转为执行 DELETE（取消收藏）。
     * <p>
     * ⚠️ 重试必须在外层（非 @Transactional），否则事务在第一次异常后标记为 rollback-only，
     * 后续重试将继续使用同一个失效事务，重试完全无效。
     */
    public boolean toggle(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        final int MAX_RETRIES = 3;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return toggleInternal(userId, sourceId, songName, artist, coverUrl);
            } catch (DuplicateKeyException e) {
                if (attempt >= MAX_RETRIES - 1) {
                    log.error("[FAVORITE] toggle 冲突重试耗尽 ({} 次), sourceId={}", MAX_RETRIES, sourceId);
                    throw e;
                }
                log.warn("[FAVORITE] toggle 并发冲突 (重试 {}/{}): sourceId={}",
                        attempt + 1, MAX_RETRIES, sourceId);
            }
        }
        return false; // unreachable
    }

    /** 实际数据库操作，每个重试创建一个新事务 */
    @Transactional(rollbackFor = Exception.class)
    protected boolean toggleInternal(Long userId, String sourceId, String songName, String artist, String coverUrl) {
        UserFavorite existing = mapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getSourceId, sourceId));
        if (existing != null) {
            mapper.deleteById(existing.getId());
            return false;
        }
        UserFavorite fav = UserFavorite.builder()
                .userId(userId).sourceId(sourceId).songName(songName)
                .artist(artist).coverUrl(coverUrl).build();
        mapper.insert(fav);
        return true;
    }

    public List<Map<String, Object>> list(Long userId, int count) {
        count = Math.max(1, Math.min(count, MAX_LIST));
        List<UserFavorite> list = mapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .orderByDesc(UserFavorite::getCreatedAt)
                .last("LIMIT " + count));
        return list.stream().map(f -> {
            Map<String, Object> m = new HashMap<>();
            m.put("sourceId", f.getSourceId());
            m.put("songName", f.getSongName());
            m.put("artist", f.getArtist());
            String c = f.getCoverUrl();
            m.put("coverUrl", c != null ? c.replace("http://", "https://") : "");
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    public Set<String> favoritesSet(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .select(UserFavorite::getSourceId)
                .last("LIMIT " + MAX_FAVORITES)).stream()
                .map(UserFavorite::getSourceId)
                .collect(Collectors.toSet());
    }

    @Transactional(rollbackFor = Exception.class)
    public int removeBatch(Long userId, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) return 0;
        return mapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .in(UserFavorite::getSourceId, sourceIds));
    }
}
