package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SearchResult;
import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * AI 推荐的验证链：把 LLM 的「提名」变成「真实存在且对得上」的曲库结果。
 *
 * <p>三段职责：
 * <ol>
 *   <li>容错解析：去 ``` 围栏 → 取第一个 { 到最后一个 } → Jackson，坏字段逐条跳过；</li>
 *   <li>逐首验证：按 4 路分块丢给共享搜索线程池，单曲失败/超时只丢该条；</li>
 *   <li>匹配打分：歌名归一化相等/互相包含 + 歌手互相包含，多候选取 finalScore 最高。</li>
 * </ol>
 * 纯内存对象，无 Spring 依赖（由 {@link AiRecommendService} 构造），匹配函数为静态纯函数便于单测。
 */
@Slf4j
final class AiRecommendVerifier {

    /** 验证并发度：单曲搜索 3s 超时，4 路并发把 12 首压到 3 轮。 */
    private static final int SEARCH_CONCURRENCY = 4;
    private static final int SEARCH_PAGE_SIZE = 3;
    private static final long SEARCH_FUTURE_TIMEOUT_SECONDS = 5L;
    /** norm() 要去掉的常见中英文标点（含全角括号/书名号/间隔号）。 */
    private static final String PUNCTUATION = "（）()【】[]《》<>·、，,。.";

    private final ObjectMapper objectMapper;
    private final SongSearchService songSearchService;
    private final ThreadPoolTaskExecutor searchExecutor;

    AiRecommendVerifier(ObjectMapper objectMapper, SongSearchService songSearchService,
                        ThreadPoolTaskExecutor searchExecutor) {
        this.objectMapper = objectMapper;
        this.songSearchService = songSearchService;
        this.searchExecutor = searchExecutor;
    }

    /** 容错解析：字段缺失的单条跳过，整体坏 JSON 返回空候选（不抛）。 */
    ParsedRecommendation parse(String raw) {
        String cleaned = raw.replace("```json", "").replace("```JSON", "").replace("```", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("[AI推荐] 响应中没有 JSON 对象");
            return new ParsedRecommendation("", List.of());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(cleaned.substring(start, end + 1), Map.class);
            String reason = root.get("reason") instanceof String r ? r : "";
            if (!(root.get("songs") instanceof List<?> list)) {
                return new ParsedRecommendation(reason, List.of());
            }
            List<Candidate> songs = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                if (!(map.get("name") instanceof String name) || name.isBlank()) continue;
                String artist = map.get("artist") instanceof String a ? a.trim() : "";
                songs.add(new Candidate(name.trim(), artist));
            }
            return new ParsedRecommendation(reason, songs);
        } catch (Exception e) {
            log.warn("[AI推荐] JSON 解析失败: {}", e.toString());
            return new ParsedRecommendation("", List.of());
        }
    }

    /** 逐首验证（4 路分块提交到共享搜索池）：返回真实命中的歌曲，顺序与候选一致。 */
    List<SongDTO> resolveAll(List<Candidate> candidates) {
        List<SongDTO> resolved = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i += SEARCH_CONCURRENCY) {
            int end = Math.min(candidates.size(), i + SEARCH_CONCURRENCY);
            List<Future<SongDTO>> futures = new ArrayList<>(end - i);
            for (int j = i; j < end; j++) {
                Candidate candidate = candidates.get(j);
                try {
                    futures.add(searchExecutor.submit(() -> resolveOne(candidate)));
                } catch (RejectedExecutionException e) {
                    log.warn("[AI推荐] 搜索线程池过载，跳过验证: {}", candidate.name());
                }
            }
            for (Future<SongDTO> future : futures) {
                try {
                    SongDTO song = future.get(SEARCH_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (song != null) resolved.add(song);
                } catch (Exception e) {
                    log.debug("[AI推荐] 单曲验证失败，丢弃: {}", e.toString());
                    future.cancel(true);
                }
            }
        }
        return resolved;
    }

    private SongDTO resolveOne(Candidate candidate) {
        String keyword = candidate.artist().isBlank()
                ? candidate.name()
                : candidate.name() + " " + candidate.artist();
        SearchResult result = songSearchService.search(keyword, 1, SEARCH_PAGE_SIZE);
        if (result == null || result.getList() == null) return null;
        return pickBestMatch(candidate.name(), candidate.artist(), result.getList());
    }

    /**
     * 在搜索结果中挑最匹配的一首。
     * <p>歌名：归一化后相等，或查询名长度≥3 时互相包含；歌手：归一化后互相包含
     * （任一方为空则放行）。多条命中取 {@code finalScore} 最高者（null 视为 0）。
     */
    static SongDTO pickBestMatch(String name, String artist, List<SongDTO> candidates) {
        if (name == null || candidates == null) return null;
        SongDTO best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (SongDTO song : candidates) {
            if (!matches(name, artist, song)) continue;
            double score = song.getFinalScore() == null ? 0d : song.getFinalScore();
            if (score > bestScore) {
                best = song;
                bestScore = score;
            }
        }
        return best;
    }

    static boolean matches(String name, String artist, SongDTO song) {
        if (song == null || song.getName() == null) return false;
        String queryName = norm(name);
        String songName = norm(song.getName());
        if (queryName.isEmpty() || songName.isEmpty()) return false;

        boolean nameMatch = queryName.equals(songName)
                || (queryName.length() >= 3 && (queryName.contains(songName) || songName.contains(queryName)));
        if (!nameMatch) return false;

        String queryArtist = norm(artist);
        String songArtist = norm(song.getArtist());
        if (queryArtist.isEmpty() || songArtist.isEmpty()) return true;
        return queryArtist.contains(songArtist) || songArtist.contains(queryArtist);
    }

    /** 归一化：小写 + 去空白 + 去常见中英文标点。 */
    static String norm(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            if (PUNCTUATION.indexOf(ch) >= 0) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    /** LLM 提名的一条候选。 */
    record Candidate(String name, String artist) {
    }

    /** LLM 响应解析结果：推荐理由 + 候选列表。 */
    record ParsedRecommendation(String reason, List<Candidate> songs) {
    }
}
