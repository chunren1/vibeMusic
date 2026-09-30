package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.UserSearchHistory;
import com.vibemusic.mapper.UserSearchHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryService {

    private final UserSearchHistoryMapper mapper;

    /** 每用户搜索历史上限（App 本地 cap 10，云端保留 50） */
    public static final int MAX_HISTORY = 50;
    private static final int MAX_KEYWORD_LEN = 200;

    /** 拉取：按搜索时间倒序返回关键词（per-user 隔离） */
    public List<String> list(Long userId, int count) {
        count = Math.max(1, Math.min(count, MAX_HISTORY));
        List<UserSearchHistory> rows = mapper.selectPage(new Page<>(1, count),
                new LambdaQueryWrapper<UserSearchHistory>()
                .eq(UserSearchHistory::getUserId, userId)
                .orderByDesc(UserSearchHistory::getSearchedAt)).getRecords();
        return rows.stream().map(UserSearchHistory::getKeyword).collect(Collectors.toList());
    }

    /**
     * 推送：upsert 关键词列表（存在则刷新时间），随后裁剪超限旧记录，
     * 返回裁剪后的云端全量供 App 对齐本地 DataStore。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> push(Long userId, List<String> keywords) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        if (keywords != null) {
            for (String raw : keywords) {
                String keyword = raw == null ? "" : raw.strip();
                if (keyword.isEmpty()) continue;
                if (keyword.length() > MAX_KEYWORD_LEN) throw new BusinessException(400, "搜索关键词过长");
                upsert(userId, keyword);
            }
            trim(userId);
        }
        return list(userId, MAX_HISTORY);
    }

    /** 清空某用户全部搜索历史，返回删除条数 */
    @Transactional(rollbackFor = Exception.class)
    public int clear(Long userId) {
        if (userId == null) throw new BusinessException(401, "请先登录");
        return mapper.delete(new LambdaQueryWrapper<UserSearchHistory>()
                .eq(UserSearchHistory::getUserId, userId));
    }

    private void upsert(Long userId, String keyword) {
        UserSearchHistory existing = mapper.selectOne(new LambdaQueryWrapper<UserSearchHistory>()
                .eq(UserSearchHistory::getUserId, userId)
                .eq(UserSearchHistory::getKeyword, keyword));
        if (existing != null) {
            existing.setSearchedAt(LocalDateTime.now());
            mapper.updateById(existing);
            return;
        }
        try {
            mapper.insert(UserSearchHistory.builder()
                    .userId(userId).keyword(keyword).searchedAt(LocalDateTime.now()).build());
        } catch (DuplicateKeyException e) {
            // 并发重复提交命中唯一键：回退为刷新时间
            log.warn("[SearchHistory] 并发重复关键词 userId={}", userId);
            UserSearchHistory dup = mapper.selectOne(new LambdaQueryWrapper<UserSearchHistory>()
                    .eq(UserSearchHistory::getUserId, userId)
                    .eq(UserSearchHistory::getKeyword, keyword));
            if (dup != null) {
                dup.setSearchedAt(LocalDateTime.now());
                mapper.updateById(dup);
            }
        }
    }

    /** 只保留最近 MAX_HISTORY 条（wrapper 写法，兼容 MySQL/H2，无方言 DELETE） */
    private void trim(Long userId) {
        Long total = mapper.selectCount(new LambdaQueryWrapper<UserSearchHistory>()
                .eq(UserSearchHistory::getUserId, userId));
        if (total != null && total > MAX_HISTORY) {
            List<Long> overflowIds = mapper.selectList(new LambdaQueryWrapper<UserSearchHistory>()
                    .eq(UserSearchHistory::getUserId, userId)
                    .orderByAsc(UserSearchHistory::getSearchedAt)
                    .last("LIMIT " + (total - MAX_HISTORY)))
                    .stream().map(UserSearchHistory::getId).collect(Collectors.toList());
            if (!overflowIds.isEmpty()) mapper.deleteBatchIds(overflowIds);
        }
    }
}
