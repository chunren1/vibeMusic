package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.User;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.security.CustomUserDetails;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiToolService 纯单元测试（Mockito 直调，不启动 Spring）。
 *
 * <p>契约重点：{@code executeTool} 永不向上抛异常，任何失败都必须转成
 * {@code {"error": "..."}} JSON 回传 LLM；成功结果的字段形状（name/artist/sourceId/platform）
 * 是喂给 LLM 的输入，形状变了模型就会答非所问，故逐字段锁定。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiToolService Function Calling 分支单元测试")
class AiToolServiceTest {

    private static final Long USER_ID = 1001L;

    @Mock
    private SongSearchService songSearchService;

    @Mock
    private PlayHistoryMapper playHistoryMapper;

    /** 用真实 ObjectMapper，序列化形状就是线上形状。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiToolService service;

    @BeforeAll
    static void initMyBatisPlusLambdaCache() {
        // LambdaQueryWrapper 解析列名依赖 TableInfo 缓存，纯单测需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                PlayHistory.class);
    }

    @BeforeEach
    void setUp() {
        service = new AiToolService(songSearchService, playHistoryMapper, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- schema ----------

    @Test
    @DisplayName("工具定义：两个工具都是 OpenAI function 格式，search_songs 必填 keyword")
    @SuppressWarnings("unchecked")
    void shouldExposeTwoOpenAiFunctionDefinitions() {
        List<Map<String, Object>> defs = service.getToolDefinitions();

        assertThat(defs).hasSize(2);
        assertThat(defs).allSatisfy(d -> assertThat(d).containsEntry("type", "function"));

        Map<String, Object> search = (Map<String, Object>) defs.get(0).get("function");
        assertThat(search).containsEntry("name", "search_songs");
        Map<String, Object> searchParams = (Map<String, Object>) search.get("parameters");
        assertThat((List<String>) searchParams.get("required")).containsExactly("keyword");
        Map<String, Object> props = (Map<String, Object>) searchParams.get("properties");
        assertThat((Map<String, Object>) props.get("limit")).containsEntry("default", 6);

        Map<String, Object> history = (Map<String, Object>) defs.get(1).get("function");
        assertThat(history).containsEntry("name", "get_user_history");
        Map<String, Object> historyParams = (Map<String, Object>) history.get("parameters");
        assertThat((List<String>) historyParams.get("required")).isEmpty();
        Map<String, Object> historyProps = (Map<String, Object>) historyParams.get("properties");
        assertThat((Map<String, Object>) historyProps.get("limit")).containsEntry("default", 10);
    }

    // ---------- search_songs ----------

    @Test
    @DisplayName("search_songs 正常：透传 keyword/limit，返回 name/artist/sourceId/platform 四字段")
    void shouldReturnSimplifiedSongShape() throws Exception {
        when(songSearchService.search("周杰伦", 1, 3)).thenReturn(SearchResult.of(List.of(
                SongDTO.builder().name("夜曲").artist("周杰伦").sourceId("186016").platform("netease").build()
        ), 1, 1, 3, "api"));

        JsonNode json = json(service.executeTool("search_songs", "{\"keyword\":\"周杰伦\",\"limit\":3}"));

        assertThat(json.get("total").asInt()).isEqualTo(1);
        JsonNode song = json.get("songs").get(0);
        assertThat(song.get("name").asText()).isEqualTo("夜曲");
        assertThat(song.get("artist").asText()).isEqualTo("周杰伦");
        assertThat(song.get("sourceId").asText()).isEqualTo("186016");
        assertThat(song.get("platform").asText()).isEqualTo("netease");
        // 字段顺序固定（LinkedHashMap），LLM 侧看到的形状稳定
        assertThat(fieldNames(song)).containsExactly("name", "artist", "sourceId", "platform");
        verify(songSearchService).search("周杰伦", 1, 3);
    }

    @Test
    @DisplayName("search_songs 空结果：songs 为空数组且 total=0，不是 null")
    void shouldReturnEmptySongsArrayWhenNoMatch() throws Exception {
        when(songSearchService.search("不存在", 1, 6))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        JsonNode json = json(service.executeTool("search_songs", "{\"keyword\":\"不存在\"}"));

        assertThat(json.get("songs").isArray()).isTrue();
        assertThat(json.get("songs")).isEmpty();
        assertThat(json.get("total").asInt()).isZero();
    }

    @Test
    @DisplayName("search_songs artist/platform 为 null 时补空串，避免 LLM 看到 null")
    void shouldReplaceNullArtistAndPlatformWithEmptyString() throws Exception {
        when(songSearchService.search("x", 1, 6)).thenReturn(SearchResult.of(List.of(
                SongDTO.builder().name("只有歌名").build()
        ), 1, 1, 6, "api"));

        JsonNode song = json(service.executeTool("search_songs", "{\"keyword\":\"x\"}")).get("songs").get(0);

        assertThat(song.get("artist").asText()).isEmpty();
        assertThat(song.get("platform").asText()).isEmpty();
    }

    @Test
    @DisplayName("search_songs 缺 keyword/limit 时回落默认值：热门歌曲 / 6")
    void shouldFallbackToDefaultsWhenArgsMissing() throws Exception {
        when(songSearchService.search("热门歌曲", 1, 6))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        service.executeTool("search_songs", "{}");

        verify(songSearchService).search("热门歌曲", 1, 6);
    }

    @Test
    @DisplayName("argumentsJson 为 null / 空白时按空参数处理，不抛异常")
    void shouldTreatNullAndBlankArgumentsAsEmpty() throws Exception {
        when(songSearchService.search("热门歌曲", 1, 6))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        assertThat(json(service.executeTool("search_songs", null)).get("total").asInt()).isZero();
        assertThat(json(service.executeTool("search_songs", "   ")).get("total").asInt()).isZero();

        verify(songSearchService, org.mockito.Mockito.times(2)).search("热门歌曲", 1, 6);
    }

    @Test
    @DisplayName("search_songs limit 类型不符（字符串/布尔）时回落 6，不抛 ClassCastException")
    void shouldFallbackLimitWhenNotNumber() throws Exception {
        when(songSearchService.search(eq("x"), eq(1), eq(6)))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        assertThat(json(service.executeTool("search_songs", "{\"keyword\":\"x\",\"limit\":\"8\"}"))
                .get("error")).isNull();
        assertThat(json(service.executeTool("search_songs", "{\"keyword\":\"x\",\"limit\":true}"))
                .get("error")).isNull();

        verify(songSearchService, org.mockito.Mockito.times(2)).search("x", 1, 6);
    }

    @Test
    @DisplayName("search_songs limit 为小数时取整数部分（6.9 → 6）")
    void shouldTruncateFractionalLimit() throws Exception {
        when(songSearchService.search("x", 1, 6)).thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        service.executeTool("search_songs", "{\"keyword\":\"x\",\"limit\":6.9}");

        verify(songSearchService).search("x", 1, 6);
    }

    @Test
    @DisplayName("search_songs keyword 显式 null 会原样透传给下游（当前无兜底，记录既有行为）")
    void shouldPassExplicitNullKeywordDownstream() throws Exception {
        when(songSearchService.search(isNull(), eq(1), eq(6)))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        service.executeTool("search_songs", "{\"keyword\":null}");

        verify(songSearchService).search(null, 1, 6);
    }

    @Test
    @DisplayName("search_songs keyword 类型不符（数字）时被兜底捕获，返回 error 而不是 500")
    void shouldReturnErrorEnvelopeWhenKeywordTypeMismatch() throws Exception {
        JsonNode json = json(service.executeTool("search_songs", "{\"keyword\":123}"));

        assertThat(json.get("error").asText()).startsWith("工具执行异常");
        verifyNoInteractions(songSearchService);
    }

    @Test
    @DisplayName("下游 search() 抛异常时被吞掉并转成 error JSON（不让 SSE 流断掉）")
    void shouldSwallowSearchFailureIntoErrorJson() throws Exception {
        when(songSearchService.search(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("musicapi down"));

        JsonNode json = json(service.executeTool("search_songs", "{\"keyword\":\"x\"}"));

        assertThat(json.get("error").asText()).contains("工具执行异常").contains("musicapi down");
        assertThat(json.get("songs")).isNull();
    }

    @Test
    @DisplayName("argumentsJson 不是合法 JSON 时返回 error 文本，不向上抛")
    void shouldReturnErrorEnvelopeOnMalformedJson() {
        String raw = service.executeTool("search_songs", "{not-json");

        assertThat(raw).startsWith("{\"error\":\"工具执行异常");
        verifyNoInteractions(songSearchService);
    }

    @Test
    @DisplayName("已知缺陷：error 信封直接拼异常消息/工具名、未做 JSON 转义，可能返回非法 JSON")
    void errorEnvelopeIsNotJsonEscaped() {
        // 异常消息里带换行与引号（Jackson 的解析错误就是这种），拼进去后整个信封不再合法
        String fromException = service.executeTool("search_songs", "{not-json");
        assertThatThrownBy(() -> objectMapper.readTree(fromException))
                .as("异常消息含换行/引号，未转义 → LLM 收到非法 JSON")
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);

        // 未知工具分支同理：函数名里的引号直接把 JSON 截断
        String fromUnknownTool = service.executeTool("bad\"name", "{}");
        assertThat(fromUnknownTool).contains("未知工具");
        assertThatThrownBy(() -> objectMapper.readTree(fromUnknownTool))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    // ---------- get_user_history ----------

    @Test
    @DisplayName("get_user_history 未登录时返回未登录 error，不查库")
    void shouldRefuseHistoryWhenNotLoggedIn() throws Exception {
        JsonNode json = json(service.executeTool("get_user_history", "{}"));

        assertThat(json.get("error").asText()).isEqualTo("未登录，无法获取播放历史");
        verifyNoInteractions(playHistoryMapper);
    }

    @Test
    @DisplayName("get_user_history 正常：返回 name/artist 两字段，artist 为 null 补空串")
    void shouldReturnSimplifiedHistoryShape() throws Exception {
        loginAs(USER_ID);
        when(playHistoryMapper.selectPage(any(), any())).thenReturn(pageOf(
                PlayHistory.builder().songName("晴天").artist("周杰伦").build(),
                PlayHistory.builder().songName("无歌手").build()));

        JsonNode json = json(service.executeTool("get_user_history", "{\"limit\":5}"));

        assertThat(json.get("total").asInt()).isEqualTo(2);
        JsonNode first = json.get("history").get(0);
        assertThat(fieldNames(first)).containsExactly("name", "artist");
        assertThat(first.get("name").asText()).isEqualTo("晴天");
        assertThat(first.get("artist").asText()).isEqualTo("周杰伦");
        assertThat(json.get("history").get(1).get("artist").asText()).isEmpty();
    }

    @Test
    @DisplayName("get_user_history 查询按当前用户过滤并按 playedAt 倒序")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldScopeHistoryQueryToCurrentUserOrderedByPlayedAtDesc() throws Exception {
        loginAs(USER_ID);
        when(playHistoryMapper.selectPage(any(), any())).thenReturn(pageOf());

        service.executeTool("get_user_history", "{}");

        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(playHistoryMapper).selectPage(any(), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = captor.getValue();
        // 先取 SQL 片段：条件表达式惰性构建，构建后参数才落到 paramNameValuePairs
        assertThat(wrapper.getSqlSegment()).contains("user_id").containsIgnoringCase("order by");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(USER_ID);
    }

    @Test
    @DisplayName("get_user_history limit 缺省 10；超 50 截到 50；0/负数兜到 1")
    void shouldClampHistoryLimit() throws Exception {
        loginAs(USER_ID);
        when(playHistoryMapper.selectPage(any(), any())).thenReturn(pageOf());

        assertThat(capturedPageSize("{}")).isEqualTo(10L);
        assertThat(capturedPageSize("{\"limit\":100}")).isEqualTo(50L);
        assertThat(capturedPageSize("{\"limit\":0}")).isEqualTo(1L);
        assertThat(capturedPageSize("{\"limit\":-5}")).isEqualTo(1L);
        assertThat(capturedPageSize("{\"limit\":\"20\"}")).isEqualTo(10L);
    }

    @Test
    @DisplayName("get_user_history 查库异常时返回 error JSON，不向上抛")
    void shouldSwallowHistoryFailureIntoErrorJson() throws Exception {
        loginAs(USER_ID);
        when(playHistoryMapper.selectPage(any(), any())).thenThrow(new RuntimeException("db down"));

        JsonNode json = json(service.executeTool("get_user_history", "{}"));

        assertThat(json.get("error").asText()).contains("工具执行异常").contains("db down");
    }

    // ---------- 未知工具 / 空函数名 ----------

    @Test
    @DisplayName("未知 functionName 走 fallback：返回带函数名的 error JSON，不抛异常")
    void shouldReturnErrorForUnknownTool() throws Exception {
        JsonNode json = json(service.executeTool("delete_all_songs", "{}"));

        assertThat(json.get("error").asText()).isEqualTo("未知工具: delete_all_songs");
        verifyNoInteractions(songSearchService, playHistoryMapper);
    }

    @Test
    @DisplayName("functionName 为 null 时被 catch 兜住（switch 抛 NPE），不向上抛")
    void shouldReturnErrorForNullFunctionName() {
        String raw = service.executeTool(null, "{}");

        assertThat(raw).startsWith("{\"error\":\"工具执行异常");
        verifyNoInteractions(songSearchService, playHistoryMapper);
    }

    @Test
    @DisplayName("搜索成功时不触碰播放历史 mapper，工具之间互不串台")
    void shouldNotTouchHistoryMapperForSearchTool() throws Exception {
        when(songSearchService.search(anyString(), anyInt(), anyInt()))
                .thenReturn(SearchResult.of(List.of(), 0, 1, 6, "api"));

        service.executeTool("search_songs", "{\"keyword\":\"x\"}");

        verify(playHistoryMapper, never()).selectPage(any(), any());
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

    @SuppressWarnings("unchecked")
    private static Page<PlayHistory> pageOf(PlayHistory... records) {
        Page<PlayHistory> page = new Page<>(1, 10);
        page.setRecords(List.of(records));
        return page;
    }

    /** 执行一次 get_user_history 并取出实际传给 mapper 的分页大小。 */
    private long capturedPageSize(String argsJson) {
        service.executeTool("get_user_history", argsJson);
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(playHistoryMapper, org.mockito.Mockito.atLeastOnce()).selectPage(captor.capture(), any());
        return captor.getValue().getSize();
    }
}
