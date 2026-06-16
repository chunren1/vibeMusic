package com.vibemusic.service;

import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 播放历史定时清理
 * <p>
 * 每天凌晨 3 点删除 7 天前的播放历史记录，
 * 防止 play_history 表无限膨胀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlayHistoryCleanupService {

    private final PlayHistoryMapper mapper;

    /** 每天凌晨 3:00 执行 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanOldHistory() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        int deleted = mapper.delete(new LambdaQueryWrapper<PlayHistory>()
                .lt(PlayHistory::getPlayedAt, sevenDaysAgo));
        if (deleted > 0) {
            log.info("播放历史清理完成: 删除 {} 条 7 天前的记录", deleted);
        }
    }
}
