package com.vibemusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.security.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 歌单工具（list_playlists / add_to_playlist / create_playlist）纯单元测试。
 *
 * <p>全部经 {@link AiToolService#executeTool} 入口调用，顺带覆盖路由与错误信封；
 * 下游 {@link PlaylistService} / {@link SongSearchService} 全部 mock，不碰真实上游。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 歌单工具（Function Calling）单元测试")
class AiPlaylistToolsTest {

    private static final Long USER_ID = 1001L;
    private static final Long PLAYLIST_ID = 10L;

    @Mock
    private SongSearchService songSearchService;

    @Mock
    private PlayHistoryMapper playHistoryMapper;

    @Mock
    private PlaylistService playlistService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiToolService service;

    @BeforeEach
    void setUp() {
        AiPlaylistTools playlistTools = new AiPlaylistTools(playlistService, songSearchService, objectMapper);
        service = new AiToolService(songSearchService, playHistoryMapper, objectMapper, playlistTools);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- list_playlists ----------

    @Test
    @DisplayName("list_playlists 未登录：返回 error，不查歌单")
    void shouldRefuseListPlaylistsWhenNotLoggedIn() throws Exception {
        JsonNode json = json(service.executeTool("list_playlists", "{}"));

        assertThat(json.get("error").asText()).isEqualTo("未登录，无法查询歌单");
        verifyNoInteractions(playlistService, songSearchService);
    }

    @Test
    @DisplayName("list_playlists 正常：playlistId/name/songCount 三字段，字段顺序固定")
    void shouldReturnPlaylistShape() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L),
                playlistRow(20L, "空歌单", 0L)));

        JsonNode json = json(service.executeTool("list_playlists", "{}"));

        assertThat(json.get("total").asInt()).isEqualTo(2);
        JsonNode first = json.get("playlists").get(0);
        assertThat(fieldNames(first)).containsExactly("playlistId", "name", "songCount");
        assertThat(first.get("playlistId").asLong()).isEqualTo(PLAYLIST_ID);
        assertThat(first.get("name").asText()).isEqualTo("华语热门精选");
        assertThat(first.get("songCount").asLong()).isEqualTo(12L);
        assertThat(json.get("playlists").get(1).get("songCount").asLong()).isZero();
    }

    // ---------- add_to_playlist ----------

    @Test
    @DisplayName("add_to_playlist 未登录：返回 error，不查歌单不搜歌")
    void shouldRefuseAddToPlaylistWhenNotLoggedIn() throws Exception {
        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"华语热门精选\",\"songName\":\"夜曲\"}"));

        assertThat(json.get("error").asText()).isEqualTo("未登录，无法添加歌曲到歌单");
        verifyNoInteractions(playlistService, songSearchService);
    }

    @Test
    @DisplayName("add_to_playlist 歌单不存在：返回 error 并提示先 list_playlists，绝不自动建歌单")
    void shouldRefuseWhenPlaylistMissing() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L)));

        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"我的私人歌单\",\"songName\":\"夜曲\"}"));

        assertThat(json.get("error").asText())
                .isEqualTo("没有名为 我的私人歌单 的歌单，可先用 list_playlists 查询");
        verify(playlistService, never()).create(any(), anyString(), any(), any());
        verifyNoInteractions(songSearchService);
    }

    @Test
    @DisplayName("add_to_playlist 歌搜不到：返回 error，不调 addSong")
    void shouldRefuseWhenSongNotFound() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L)));
        when(songSearchService.search("夜曲 周杰伦", 1, 3))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 3, "api"));

        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"华语热门精选\",\"songName\":\"夜曲\",\"artist\":\"周杰伦\"}"));

        assertThat(json.get("error").asText()).isEqualTo("没有找到歌曲: 夜曲 周杰伦");
        verify(playlistService, never()).addSong(any(), any(), anyString(), anyString(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("add_to_playlist 正常：搜索关键词=歌名+歌手，addSong 参数完整（含 platform/封面/时长）")
    void shouldAddSongWithCorrectArguments() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L)));
        when(songSearchService.search("夜曲 周杰伦", 1, 3, "netease"))
                .thenReturn(SearchResult.of(List.of(song("夜曲", "周杰伦", "186016", "netease")), 1, 1, 3, "api"));
        when(playlistService.addSong(USER_ID, PLAYLIST_ID, "186016", "夜曲", "周杰伦",
                "https://cover/186016", 200, "netease")).thenReturn(true);

        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"华语热门精选\",\"songName\":\"夜曲\",\"artist\":\"周杰伦\",\"platform\":\"netease\"}"));

        assertThat(json.get("added").asBoolean()).isTrue();
        assertThat(json.get("playlistId").asLong()).isEqualTo(PLAYLIST_ID);
        assertThat(json.get("playlistName").asText()).isEqualTo("华语热门精选");
        assertThat(fieldNames(json)).containsExactly("playlistId", "playlistName", "added", "message", "song");
        assertThat(fieldNames(json.get("song"))).containsExactly("name", "artist", "sourceId", "platform");
        verify(playlistService).addSong(USER_ID, PLAYLIST_ID, "186016", "夜曲", "周杰伦",
                "https://cover/186016", 200, "netease");
    }

    @Test
    @DisplayName("add_to_playlist 只给歌名：走三参搜索（platform 缺省=全部平台）")
    void shouldSearchAllPlatformsWhenPlatformMissing() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L)));
        when(songSearchService.search("晴天", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("晴天", "周杰伦", "186001", "qq")), 1, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(true);

        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"华语热门精选\",\"songName\":\"晴天\"}"));

        assertThat(json.get("song").get("platform").asText()).isEqualTo("qq");
        verify(songSearchService).search("晴天", 1, 3);
    }

    @Test
    @DisplayName("add_to_playlist 歌已在歌单（addSong=false）：added=false 且说明未重复添加")
    void shouldReportDuplicateSong() throws Exception {
        loginAs(USER_ID);
        when(playlistService.listPlaylists(USER_ID)).thenReturn(List.of(
                playlistRow(PLAYLIST_ID, "华语热门精选", 12L)));
        when(songSearchService.search("晴天", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("晴天", "周杰伦", "186001", "netease")), 1, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(false);

        JsonNode json = json(service.executeTool("add_to_playlist",
                "{\"playlistName\":\"华语热门精选\",\"songName\":\"晴天\"}"));

        assertThat(json.get("added").asBoolean()).isFalse();
        assertThat(json.get("message").asText()).contains("已在歌单中");
    }

    @Test
    @DisplayName("add_to_playlist 缺 songName：返回 error，不查歌单")
    void shouldRefuseWhenSongNameMissing() throws Exception {
        loginAs(USER_ID);

        JsonNode json = json(service.executeTool("add_to_playlist", "{\"playlistName\":\"华语热门精选\"}"));

        assertThat(json.get("error").asText()).isEqualTo("缺少参数 songName");
        verifyNoInteractions(playlistService, songSearchService);
    }

    // ---------- create_playlist ----------

    @Test
    @DisplayName("create_playlist 未登录：返回 error，不建歌单")
    void shouldRefuseCreatePlaylistWhenNotLoggedIn() throws Exception {
        JsonNode json = json(service.executeTool("create_playlist", "{\"name\":\"新歌单\"}"));

        assertThat(json.get("error").asText()).isEqualTo("未登录，无法创建歌单");
        verifyNoInteractions(playlistService, songSearchService);
    }

    @Test
    @DisplayName("create_playlist 缺 name：返回 error，不建歌单")
    void shouldRefuseWhenNameMissing() throws Exception {
        loginAs(USER_ID);

        JsonNode json = json(service.executeTool("create_playlist", "{}"));

        assertThat(json.get("error").asText()).isEqualTo("缺少参数 name");
        verifyNoInteractions(playlistService, songSearchService);
    }

    @Test
    @DisplayName("create_playlist songs 为空：只建空歌单，added=0/failed 空，不搜歌")
    void shouldCreateEmptyPlaylistWhenNoSongs() throws Exception {
        loginAs(USER_ID);
        when(playlistService.create(USER_ID, "我的歌单", null, null)).thenReturn(created(PLAYLIST_ID, "我的歌单"));

        JsonNode json = json(service.executeTool("create_playlist", "{\"name\":\"我的歌单\"}"));

        assertThat(json.get("playlistId").asLong()).isEqualTo(PLAYLIST_ID);
        assertThat(json.get("name").asText()).isEqualTo("我的歌单");
        assertThat(json.get("added").asInt()).isZero();
        assertThat(json.get("failed")).isEmpty();
        assertThat(fieldNames(json)).containsExactly("playlistId", "name", "added", "failed");
        verifyNoInteractions(songSearchService);
    }

    @Test
    @DisplayName("create_playlist 全部成功：逐首搜索并 addSong，added=2/failed 空")
    void shouldCreatePlaylistWithAllSongs() throws Exception {
        loginAs(USER_ID);
        when(playlistService.create(USER_ID, "我的歌单", "desc", null))
                .thenReturn(created(PLAYLIST_ID, "我的歌单"));
        when(songSearchService.search("晴天 周杰伦", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("晴天", "周杰伦", "1", "netease")), 1, 1, 3, "api"));
        when(songSearchService.search("夜曲", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("夜曲", "周杰伦", "2", "netease")), 1, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(true);

        JsonNode json = json(service.executeTool("create_playlist",
                "{\"name\":\"我的歌单\",\"description\":\"desc\",\"songs\":[\"晴天 周杰伦\",\"夜曲\"]}"));

        assertThat(json.get("added").asInt()).isEqualTo(2);
        assertThat(json.get("failed")).isEmpty();
        verify(playlistService, times(2)).addSong(eq(USER_ID), eq(PLAYLIST_ID), anyString(), anyString(),
                any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("create_playlist 部分歌曲搜不到：added 只算成功数，failed 列出失败歌名，整体不失败")
    void shouldReportPartialFailure() throws Exception {
        loginAs(USER_ID);
        when(playlistService.create(USER_ID, "我的歌单", null, null)).thenReturn(created(PLAYLIST_ID, "我的歌单"));
        when(songSearchService.search("晴天", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("晴天", "周杰伦", "1", "netease")), 1, 1, 3, "api"));
        when(songSearchService.search("找不到的歌", 1, 3))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(true);

        JsonNode json = json(service.executeTool("create_playlist",
                "{\"name\":\"我的歌单\",\"songs\":[\"晴天\",\"找不到的歌\"]}"));

        assertThat(json.get("error")).isNull();
        assertThat(json.get("added").asInt()).isEqualTo(1);
        assertThat(json.get("failed")).hasSize(1);
        assertThat(json.get("failed").get(0).asText()).isEqualTo("找不到的歌");
    }

    @Test
    @DisplayName("create_playlist 单首 addSong 抛异常：该首进 failed，其余照常加入")
    void shouldIsolateSingleSongFailure() throws Exception {
        loginAs(USER_ID);
        when(playlistService.create(USER_ID, "我的歌单", null, null)).thenReturn(created(PLAYLIST_ID, "我的歌单"));
        when(songSearchService.search("晴天", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("晴天", "周杰伦", "1", "netease")), 1, 1, 3, "api"));
        when(songSearchService.search("夜曲", 1, 3))
                .thenReturn(SearchResult.of(List.of(song("夜曲", "周杰伦", "2", "netease")), 1, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), eq("1"), anyString(), any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("db hiccup"));
        when(playlistService.addSong(any(), any(), eq("2"), anyString(), any(), any(), any(), anyString()))
                .thenReturn(true);

        JsonNode json = json(service.executeTool("create_playlist",
                "{\"name\":\"我的歌单\",\"songs\":[\"晴天\",\"夜曲\"]}"));

        assertThat(json.get("added").asInt()).isEqualTo(1);
        assertThat(json.get("failed").get(0).asText()).isEqualTo("晴天");
    }

    @Test
    @DisplayName("create_playlist songs 超 20 首：只搜索/加入前 20 首，防上游被打爆")
    void shouldTruncateSongsToTwenty() throws Exception {
        loginAs(USER_ID);
        when(playlistService.create(USER_ID, "大歌单", null, null)).thenReturn(created(PLAYLIST_ID, "大歌单"));
        when(songSearchService.search(anyString(), eq(1), eq(3)))
                .thenReturn(SearchResult.of(List.of(song("随便", "某人", "9", "netease")), 1, 1, 3, "api"));
        when(playlistService.addSong(any(), any(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(true);

        List<String> songs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            songs.add("歌曲" + i);
        }
        String args = objectMapper.writeValueAsString(Map.of("name", "大歌单", "songs", songs));

        JsonNode json = json(service.executeTool("create_playlist", args));

        assertThat(json.get("added").asInt()).isEqualTo(AiPlaylistTools.MAX_SONGS_PER_CALL);
        assertThat(json.get("failed")).isEmpty();
        verify(songSearchService, times(AiPlaylistTools.MAX_SONGS_PER_CALL))
                .search(anyString(), eq(1), eq(3));
    }

    // ---------- helpers ----------

    private static void loginAs(Long userId) {
        User user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new CustomUserDetails(user), null, List.of()));
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 模拟 PlaylistService.listPlaylists 的行形状（id/name/songCount）。 */
    private static Map<String, Object> playlistRow(Long id, String name, long songCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("songCount", songCount);
        return row;
    }

    /** 模拟 PlaylistService.create 的返回形状。 */
    private static Map<String, Object> created(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static SongDTO song(String name, String artist, String sourceId, String platform) {
        return SongDTO.builder()
                .name(name).artist(artist).sourceId(sourceId).platform(platform)
                .coverUrl("https://cover/" + sourceId).duration(200)
                .build();
    }
}
