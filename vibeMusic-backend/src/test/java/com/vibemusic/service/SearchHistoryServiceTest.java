package com.vibemusic.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.UserSearchHistory;
import com.vibemusic.mapper.UserSearchHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SearchHistoryService 单元测试")
class SearchHistoryServiceTest {

    private UserSearchHistoryMapper mapper;
    private SearchHistoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserSearchHistoryMapper.class);
        service = new SearchHistoryService(mapper);
        lenient().when(mapper.selectCount(any())).thenReturn(1L);
        lenient().when(mapper.selectPage(any(), any())).thenReturn(new Page<>());
    }

    @Test
    @DisplayName("push → 新关键词 insert 并返回云端列表")
    void shouldInsertNewKeyword() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(UserSearchHistory.class))).thenReturn(1);

        service.push(1L, List.of("周杰伦"));

        verify(mapper).insert(org.mockito.ArgumentMatchers.<UserSearchHistory>argThat(r ->
                Long.valueOf(1L).equals(r.getUserId()) && "周杰伦".equals(r.getKeyword())));
        verify(mapper, never()).updateById(any(UserSearchHistory.class));
    }

    @Test
    @DisplayName("push → 已存在关键词刷新时间而不 insert（同用户 upsert）")
    void shouldRefreshExistingKeyword() {
        UserSearchHistory existing = UserSearchHistory.builder()
                .id(7L).userId(1L).keyword("晴天").searchedAt(LocalDateTime.now().minusDays(1)).build();
        when(mapper.selectOne(any())).thenReturn(existing);

        service.push(1L, List.of("晴天"));

        verify(mapper, never()).insert(any(UserSearchHistory.class));
        verify(mapper).updateById(org.mockito.ArgumentMatchers.<UserSearchHistory>argThat(r ->
                Long.valueOf(7L).equals(r.getId())));
    }

    @Test
    @DisplayName("push → 空白关键词跳过，不碰数据库写")
    void shouldSkipBlankKeywords() {
        service.push(1L, java.util.Arrays.asList("  ", "", null));

        verify(mapper, never()).insert(any(UserSearchHistory.class));
        verify(mapper, never()).updateById(any(UserSearchHistory.class));
    }

    @Test
    @DisplayName("push → 超长关键词抛 400 BusinessException")
    void shouldRejectTooLongKeyword() {
        assertThatThrownBy(() -> service.push(1L, List.of("k".repeat(201))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("push → 未登录抛 401 BusinessException")
    void shouldRejectNullUser() {
        assertThatThrownBy(() -> service.push(null, List.of("晴天")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("list → count 钳制到 MAX_HISTORY(50)，且按用户隔离查询")
    void shouldCapCountAndIsolateUser() {
        service.list(1L, 500);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<UserSearchHistory>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("push → 超 50 条时删除最旧溢出记录")
    void shouldTrimOverflow() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(55L);
        List<UserSearchHistory> oldest = List.of(
                UserSearchHistory.builder().id(1L).userId(1L).keyword("a").build(),
                UserSearchHistory.builder().id(2L).userId(1L).keyword("b").build(),
                UserSearchHistory.builder().id(3L).userId(1L).keyword("c").build(),
                UserSearchHistory.builder().id(4L).userId(1L).keyword("d").build(),
                UserSearchHistory.builder().id(5L).userId(1L).keyword("e").build());
        when(mapper.selectList(any())).thenReturn(oldest);

        service.push(1L, List.of("新词"));

        verify(mapper).deleteBatchIds(List.of(1L, 2L, 3L, 4L, 5L));
    }

    @Test
    @DisplayName("clear → 按用户删除并返回条数；未登录抛 401")
    void shouldClearByUser() {
        when(mapper.delete(any())).thenReturn(3);

        assertThat(service.clear(1L)).isEqualTo(3);
        verify(mapper).delete(any());
        assertThatThrownBy(() -> service.clear(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(401);
    }
}
