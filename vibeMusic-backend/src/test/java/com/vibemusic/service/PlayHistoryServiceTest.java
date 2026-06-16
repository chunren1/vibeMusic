package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
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
}
