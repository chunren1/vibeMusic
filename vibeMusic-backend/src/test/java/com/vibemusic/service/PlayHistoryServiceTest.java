package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.mapper.PlayHistoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PlayHistoryService 单元测试")
class PlayHistoryServiceTest extends TransactionalServiceTest {

    @Autowired
    private PlayHistoryService playHistoryService;

    @Autowired
    private PlayHistoryMapper playHistoryMapper;

    @Test
    @DisplayName("record → 记录播放历史")
    void shouldRecordPlayHistory() {
        playHistoryService.record(1L, "newPlay001", "新播放", "歌手X", "http://cover.jpg");
        List<Map<String, Object>> recent = playHistoryService.recent(1L, 10);
        assertThat(recent).isNotEmpty();
        // 新记录应包含在结果中（可能不是第一位，因为 data-test.sql 有已有记录）
        assertThat(recent.stream().anyMatch(m -> "newPlay001".equals(m.get("sourceId")))).isTrue();
    }

    @Test
    @DisplayName("recent → 返回最近播放（去重 sourceId）")
    void shouldDeduplicateBySourceId() {
        // 同一首歌播放两次
        playHistoryService.record(1L, "sameSong", "同一首歌", "歌手", null);
        playHistoryService.record(1L, "sameSong", "同一首歌", "歌手", null);
        List<Map<String, Object>> recent = playHistoryService.recent(1L, 10);
        // 应该只出现一次
        long count = recent.stream().filter(m -> "sameSong".equals(m.get("sourceId"))).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("recent → count 上限为 MAX_HISTORY(300)")
    void shouldCapAtMaxHistory() {
        List<Map<String, Object>> recent = playHistoryService.recent(1L, 500);
        assertThat(recent.size()).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("recent → 无播放历史返回空列表")
    void shouldReturnEmptyForNewUser() {
        List<Map<String, Object>> recent = playHistoryService.recent(999L, 10);
        assertThat(recent).isEmpty();
    }

    @Test
    @DisplayName("BUG: Builder 创建 PlayHistory 时 playedAt 不应为 null（依赖 DB DEFAULT）")
    void shouldNotInsertNullPlayedAtWhenUsingBuilder() {
        // 模拟使用 @Builder 创建 PlayHistory 但忘记设置 playedAt 的场景
        PlayHistory history = PlayHistory.builder()
                .userId(1L)
                .sourceId("builderTestSong")
                .songName("Builder测试")
                .artist("测试歌手")
                .coverUrl("http://test.jpg")
                .build();
        // 此时 playedAt 应为 null（Builder 未设置）
        assertThat(history.getPlayedAt()).isNull();

        // 插入数据库
        playHistoryMapper.insert(history);

        // 读回验证：playedAt 应由 DB DEFAULT CURRENT_TIMESTAMP 自动填充，不应为 null
        PlayHistory loaded = playHistoryMapper.selectById(history.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getPlayedAt())
                .as("使用 Builder 插入后 playedAt 不应为 null，应由 DB DEFAULT CURRENT_TIMESTAMP 填充")
                .isNotNull();
    }
}
