package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.entity.User;
import com.vibemusic.security.CustomUserDetails;
import com.vibemusic.service.SearchHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SearchHistoryController 契约单元测试")
class SearchHistoryControllerTest {

    private SearchHistoryService service;
    private SearchHistoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(SearchHistoryService.class);
        controller = new SearchHistoryController(service);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        User user = User.builder().id(1L).username("testuser").password("encoded").enabled(true).build();
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    @Test
    @DisplayName("pull → 未登录返回 401，不调用 service")
    void shouldRejectPullWhenAnonymous() {
        Result<List<String>> result = controller.pull(20);

        assertThat(result.getCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("pull → 登录返回云端关键词列表")
    void shouldPullWhenLoggedIn() {
        login();
        when(service.list(1L, 20)).thenReturn(List.of("晴天", "七里香"));

        Result<List<String>> result = controller.pull(20);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsExactly("晴天", "七里香");
        verify(service).list(1L, 20);
    }

    @Test
    @DisplayName("push → 未登录返回 401，不调用 service")
    void shouldRejectPushWhenAnonymous() {
        Result<List<String>> result = controller.push(Map.of("keywords", List.of("晴天")));

        assertThat(result.getCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("push → 缺 keywords 返回 400")
    void shouldRejectPushWithoutKeywords() {
        login();

        assertThat(controller.push(new HashMap<>()).getCode()).isEqualTo(400);
        assertThat(controller.push(null).getCode()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("push → 登录后透传 keywords 并返回同步后列表")
    void shouldPushWhenLoggedIn() {
        login();
        when(service.push(1L, List.of("晴天"))).thenReturn(List.of("晴天"));

        Result<List<String>> result = controller.push(Map.of("keywords", List.of("晴天")));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsExactly("晴天");
        verify(service).push(1L, List.of("晴天"));
    }

    @Test
    @DisplayName("clear → 未登录 401；登录后返回清空条数")
    void shouldClearContract() {
        assertThat(controller.clear().getCode()).isEqualTo(401);

        login();
        when(service.clear(1L)).thenReturn(2);

        Result<Integer> result = controller.clear();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(2);
        verify(service).clear(1L);
    }
}
