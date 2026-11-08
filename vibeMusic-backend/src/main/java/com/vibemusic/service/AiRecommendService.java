package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.config.RestTemplateConfig;
import com.vibemusic.controller.AssistantClient;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.UserFavorite;
import com.vibemusic.mapper.PlayHistoryMapper;
import com.vibemusic.mapper.UserFavoriteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 歌曲推荐：LLM 只负责「提名」，真实曲库负责「坐实」。
 *
 * <p>流程：缓存命中 → 每日配额 → 采样播放历史/收藏 → LLM 出候选（歌名+歌手）→
 * 逐首走真实搜索验证（见 {@link AiRecommendVerifier}）→ 命中达阈值才写 24h 缓存并返回，
 * 否则降级为空。任何异常都不抛给客户端（唯一例外：refresh=true 且配额用完 → 429）。
 *
 * <p>为什么不让 LLM 直接给元数据：模型会编造不存在的歌/歌手，直接透传等于把幻觉写进客户端缓存。
 */
@Slf4j
@Service
public class AiRecommendService {

    static final String CACHE_KEY_PREFIX = "recommend:ai:v1:user:";
    static final String QUOTA_KEY_PREFIX = "recommend:ai:user:";
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final String TYPE_AI = "ai";

    /** 单次 AI 调用的总预算（与助手一致：45s，含一次 LLM 调用）。 */
    private static final Duration AI_TOTAL_BUDGET = Duration.ofSeconds(45);
    private static final String SYSTEM_PROMPT = "你是严谨的音乐推荐引擎，只输出 JSON，绝不编造歌曲。";

    private final PlayHistoryMapper playHistoryMapper;
    private final UserFavoriteMapper userFavoriteMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final AssistantClient assistantClient;
    private final AiRecommendVerifier verifier;
    private final boolean enabled;
    private final int dailyLimit;
    private final int candidates;
    private final int minResolved;
    private final int historySample;
    private final int favoriteSample;

    public AiRecommendService(PlayHistoryMapper playHistoryMapper,
                              UserFavoriteMapper userFavoriteMapper,
                              SongSearchService songSearchService,
                              StringRedisTemplate stringRedisTemplate,
                              ObjectMapper objectMapper,
                              RateLimitService rateLimitService,
                              ThreadPoolTaskExecutor searchExecutor,
                              AssistantClient assistantClient,
                              @Value("${recommend.ai.enabled:true}") boolean enabled,
                              @Value("${recommend.ai.daily-limit:20}") int dailyLimit,
                              @Value("${recommend.ai.candidates:12}") int candidates,
                              @Value("${recommend.ai.min-resolved:6}") int minResolved,
                              @Value("${recommend.ai.history-sample:12}") int historySample,
                              @Value("${recommend.ai.favorite-sample:6}") int favoriteSample) {
        this.playHistoryMapper = playHistoryMapper;
        this.userFavoriteMapper = userFavoriteMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.assistantClient = assistantClient;
        this.verifier = new AiRecommendVerifier(objectMapper, songSearchService, searchExecutor);
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.candidates = candidates;
        this.minResolved = minResolved;
        this.historySample = historySample;
        this.favoriteSample = favoriteSample;
    }

    /**
     * AI 推荐入口。
     *
     * @param userId  null=未登录（App 据此隐藏板块，不报错）
     * @param refresh true=跳过缓存并强制消耗一次配额；配额用完抛 429
     */
    public RecommendResult getAiRecommend(Long userId, boolean refresh) {
        if (!enabled || userId == null) return empty();

        String cacheKey = CACHE_KEY_PREFIX + userId;
        try {
            if (!refresh) {
                RecommendResult cached = readCache(cacheKey);
                if (cached != null) return cached;
            }

            if (!acquireQuota(userId)) {
                if (refresh) throw new BusinessException(429, quotaExhaustedMessage());
                // 非刷新场景配额用完：有缓存早返回了，这里只能空降级，不打扰用户
                log.info("[AI推荐] 今日配额已用完，降级为空: userId={}", userId);
                return empty();
            }

            return generate(userId, cacheKey);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[AI推荐] 生成失败，降级为空: userId={}, err={}", userId, e.toString());
            return empty();
        }
    }

    // ============================ 生成主链路 ============================

    private RecommendResult generate(Long userId, String cacheKey) {
        List<PlayHistory> history = loadHistory(userId);
        if (history.isEmpty()) {
            log.debug("[AI推荐] 无播放历史，不硬凑: userId={}", userId);
            return empty();
        }
        Set<String> playedIds = history.stream()
                .map(PlayHistory::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        String raw = callModel(buildPrompt(history, loadFavorites(userId)));
        if (raw == null || raw.isBlank()) {
            log.warn("[AI推荐] LLM 未返回内容: userId={}", userId);
            return empty();
        }

        AiRecommendVerifier.ParsedRecommendation parsed = verifier.parse(raw);
        if (parsed.songs().isEmpty()) {
            log.warn("[AI推荐] LLM 候选为空: userId={}", userId);
            return empty();
        }

        Map<String, SongDTO> deduped = new LinkedHashMap<>();
        for (SongDTO song : verifier.resolveAll(parsed.songs())) {
            if (song.getSourceId() == null || playedIds.contains(song.getSourceId())) continue;
            deduped.putIfAbsent(song.getSourceId(), song);
        }
        List<SongDTO> songs = new ArrayList<>(deduped.values());

        if (songs.size() < minResolved) {
            // 不写缓存：把「候选多少 / 命中多少」打进日志，方便后续调 prompt，而不是把不足量结果缓存 24h
            log.warn("[AI推荐] 命中不足，降级为空: 候选 {} / 命中 {}", parsed.songs().size(), songs.size());
            return empty();
        }

        RecommendResult result = RecommendResult.builder()
                .songs(songs)
                .greeting("")
                .reason(parsed.reason())
                .type(TYPE_AI)
                .build();
        writeCache(cacheKey, result);
        log.debug("[AI推荐] 生成完成: userId={}, songs={}", userId, songs.size());
        return result;
    }

    private List<PlayHistory> loadHistory(Long userId) {
        Page<PlayHistory> page = playHistoryMapper.selectPage(new Page<>(1, historySample),
                new LambdaQueryWrapper<PlayHistory>()
                        .eq(PlayHistory::getUserId, userId)
                        .orderByDesc(PlayHistory::getPlayedAt));
        return page == null || page.getRecords() == null ? List.of() : page.getRecords();
    }

    private List<UserFavorite> loadFavorites(Long userId) {
        Page<UserFavorite> page = userFavoriteMapper.selectPage(new Page<>(1, favoriteSample),
                new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .orderByDesc(UserFavorite::getCreatedAt));
        return page == null || page.getRecords() == null ? List.of() : page.getRecords();
    }

    /**
     * 组提示词（2026-11-07 压缩版）：只保留模型真正需要的信号，全程中文短句。
     *
     * <p>省 token 的三处：① 删掉「输出要求」四条编号规则，合并成一行；
     * ② 只用「歌 - 歌手」列表，重复条目去重；③ 输出契约用短键（r/s/n/a），
     * 解析侧兼容长短两套键（见 {@link AiRecommendVerifier#parse}）。
     */
    private String buildPrompt(List<PlayHistory> history, List<UserFavorite> favorites) {
        StringBuilder sb = new StringBuilder();
        sb.append("推荐 ").append(candidates).append(" 首真实存在的主流歌曲，风格参考下面画像，不要重复「已听」里的歌。\n");
        sb.append("已听：\n");
        appendTrackLines(sb, history.stream()
                .map(h -> trackLine(h.getSongName(), h.getArtist()))
                .toList());
        List<String> favLines = favorites.stream()
                .map(f -> trackLine(f.getSongName(), f.getArtist()))
                .filter(l -> !l.isEmpty())
                .distinct()
                .limit(favoriteSample)
                .toList();
        if (!favLines.isEmpty()) {
            sb.append("收藏：\n");
            appendTrackLines(sb, favLines);
        }
        sb.append("\n只输出 JSON：{\"r\":\"30字内中文理由\",\"s\":[{\"n\":\"歌名\",\"a\":\"歌手\"}]}，")
          .append("s 恰好 ").append(candidates).append(" 条、不重复、歌名与歌手必须真实可搜到；不要解释、不要 Markdown。");
        return sb.toString();
    }

    private static String trackLine(String name, String artist) {
        String n = nullSafe(name).trim();
        if (n.isEmpty()) return "";
        String a = nullSafe(artist).trim();
        return a.isEmpty() ? n : n + " - " + a;
    }

    private static void appendTrackLines(StringBuilder sb, List<String> lines) {
        lines.stream().filter(l -> !l.isEmpty()).distinct().forEach(l -> sb.append("- ").append(l).append('\n'));
    }

    private String callModel(String prompt) {
        AiCallBudget budget = AiCallBudget.start(AI_TOTAL_BUDGET, RestTemplateConfig.AI_RESPONSE_TIMEOUT);
        if (!budget.canCall()) return null;
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt));
        Map<String, Object> choice = assistantClient.callLLM(messages, budget);
        if (choice == null) return null;
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> map)) return null;
        Object content = map.get("content");
        return content instanceof String text ? text : null;
    }

    // ============================ 缓存与配额 ============================

    private RecommendResult readCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, RecommendResult.class);
        } catch (Exception e) {
            log.warn("[AI推荐] 读取缓存失败: {} ({})", key, e.toString());
            return null;
        }
    }

    private void writeCache(String key, RecommendResult result) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[AI推荐] 写入缓存失败: {} ({})", key, e.toString());
        }
    }

    private boolean acquireQuota(Long userId) {
        String key = QUOTA_KEY_PREFIX + userId + ":" + LocalDate.now();
        return rateLimitService.tryAcquire(key, dailyLimit, Duration.ofDays(1));
    }

    /** 429 文案按实际配置生成：改 daily-limit 不用再回来改字符串（2026-11-07 教训）。 */
    private String quotaExhaustedMessage() {
        return "今天的 AI 推荐次数已用完（" + dailyLimit + " 次/天），明天再来";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static RecommendResult empty() {
        return RecommendResult.builder()
                .songs(List.of())
                .greeting("")
                .reason("")
                .type(TYPE_AI)
                .build();
    }
}
