package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PlayHistoryCleanupService 单元测试")
class PlayHistoryCleanupServiceTest extends TransactionalServiceTest {

    @Autowired
    private PlayHistoryCleanupService cleanupService;

    @Autowired
    private PlayHistoryMapper playHistoryMapper;

    @Test
    @DisplayName("cleanOldHistory → 删除 7 天前的记录")
    void shouldDeleteOldRecords() {
        // 先手动插入一条 7 天前的记录（模拟过期数据）
        PlayHistory old = PlayHistory.builder()
                .userId(1L).sourceId("oldSong")
                .songName("老歌").artist("老歌手")
                .build();
        // 使用 MyBatis 原生操作绕过自动填充时间
        old.setPlayedAt(LocalDateTime.now().minusDays(8));
        playHistoryMapper.insert(old);

        // 执行清理
        cleanupService.cleanOldHistory();

        // 验证旧记录被删除
        List<PlayHistory> remaining = playHistoryMapper.selectList(
                new LambdaQueryWrapper<PlayHistory>().eq(PlayHistory::getSourceId, "oldSong"));
        assertThat(remaining).isEmpty();
    }

    @Test
    @DisplayName("cleanOldHistory → 不影响 7 天内的记录")
    void shouldKeepRecentRecords() {
        // 插入一条今天的记录
        PlayHistory recent = PlayHistory.builder()
                .userId(1L).sourceId("recentSong")
                .songName("新歌").artist("新歌手")
                .build();
        playHistoryMapper.insert(recent);

        cleanupService.cleanOldHistory();

        // 验证新记录保留
        List<PlayHistory> remaining = playHistoryMapper.selectList(
                new LambdaQueryWrapper<PlayHistory>().eq(PlayHistory::getSourceId, "recentSong"));
        assertThat(remaining).isNotEmpty();
    }
}
