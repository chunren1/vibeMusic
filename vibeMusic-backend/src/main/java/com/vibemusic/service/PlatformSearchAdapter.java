package com.vibemusic.service;

import com.vibemusic.common.resilience.UpstreamBreaker;
import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 平台适配层（从 SongSearchService 抽出，行为不变）：五个上游平台的单次搜索、
 * 解析、超时等待与失败上报。
 *
 * <p>职责边界：本类只负责「向某个平台要数据，并把结果/失败状态如实上报」；
 * 缓存、单飞、分页、合并排序仍在调用方。所有方法都不抛异常——上游任何失败
 * 都降级为空列表 + 失败标记，由调用方决定是否给用户重试提示。
 *
 * <p>熔断器与降权计数由调用方注入（共享同一实例，故跨类状态一致）。
 */
@Slf4j
class PlatformSearchAdapter {

    /** 单平台单次拉取条数（合并多源后由 SongMerger 去重排序）。 */
    private static final int PER_PLATFORM_FETCH = 40;

    /** 单平台等待预算（秒）；fanout 另有全局 deadline 兜底。 */
    static final int SEARCH_TIMEOUT_SEC = 4;

    /** QQ 预算更紧：QQ 链路最慢，超时后还有 4 个平台的结果可用。 */
    static final int QQ_TIMEOUT_SEC = 2;

    private final NeteaseApiService neteaseApiService;
    private final UpstreamBreaker upstreamBreaker;
    private final QqCircuitBreaker qqBreaker;
    /** 平台成败回报（platform, success）——用于动态源降权。 */
    private final BiConsumer<String, Boolean> sourceOutcome;

    PlatformSearchAdapter(NeteaseApiService neteaseApiService,
                          UpstreamBreaker upstreamBreaker,
                          QqCircuitBreaker qqBreaker,
                          BiConsumer<String, Boolean> sourceOutcome) {
        this.neteaseApiService = neteaseApiService;
        this.upstreamBreaker = upstreamBreaker;
        this.qqBreaker = qqBreaker;
        this.sourceOutcome = sourceOutcome;
    }

    /**
     * 网关整体熔断的失败上报：仅在传输层失败（连接拒绝/超时/5xx）时计数；
     * 4xx/解析失败等业务侧问题只清理半开探针占位，不误导开路。
     */
    void recordUpstreamFailure(Throwable error) {
        upstreamBreaker.recordAttemptFailure(UpstreamBreaker.isTransportFailure(error));
    }

    /** 全失败判定：实际发起的平台全部异常/超时（熔断跳过的 QQ 不计入，被跳过时只看网易云+咪咕+酷狗+B站）。 */
    static boolean allUpstreamFailed(boolean qqSkipped, AtomicBoolean neFailed,
                                     AtomicBoolean qqFailed, AtomicBoolean mgFailed,
                                     AtomicBoolean kgFailed, AtomicBoolean biFailed) {
        if (!neFailed.get() || !mgFailed.get() || !kgFailed.get() || !biFailed.get()) return false;
        return qqSkipped || qqFailed.get();
    }

    /** 单次搜索内恰好计数一次：超时分支与任务收尾分支竞争，胜者计数，败者跳过。 */
    void recordQqOutcomeOnce(AtomicBoolean claimed, boolean success) {
        if (claimed.compareAndSet(false, true)) {
            if (success) qqBreaker.recordSuccess();
            else qqBreaker.recordFailure();
        }
    }

    @SuppressWarnings("unchecked")
    List<SongDTO> safeSearchNetease(String keyword, AtomicBoolean failed) {
        return safeSearchNetease(keyword, failed, null);
    }

    @SuppressWarnings("unchecked")
    List<SongDTO> safeSearchNetease(String keyword, AtomicBoolean failed, String userCookie) {
        try {
            Map<String, Object> result = userCookie != null
                    ? neteaseApiService.searchNetease(keyword, PER_PLATFORM_FETCH, userCookie)
                    : neteaseApiService.searchNetease(keyword, PER_PLATFORM_FETCH);
            // 传输层成功（拿到 HTTP 200 响应体）即视为网关健康：200 空结果也算成功
            upstreamBreaker.recordSuccess();
            return toDtos(result);
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            recordUpstreamFailure(e);
            log.error("Netease search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    List<SongDTO> fetchQQ(String keyword) {
        return toDtos(neteaseApiService.searchQQ(keyword, PER_PLATFORM_FETCH));
    }

    /** 同步单平台 QQ 搜索：成功清零、异常计数（单次调用恰好计数一次）。 */
    List<SongDTO> safeSearchQQ(String keyword) {
        try {
            List<SongDTO> songs = fetchQQ(keyword);
            upstreamBreaker.recordSuccess();
            qqBreaker.recordSuccess();
            sourceOutcome.accept("qq", true);
            return songs;
        } catch (Exception e) {
            recordUpstreamFailure(e);
            qqBreaker.recordFailure();
            sourceOutcome.accept("qq", false);
            log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    List<SongDTO> safeSearchMigu(String keyword, AtomicBoolean failed) {
        try {
            Map<String, Object> result = neteaseApiService.searchMigu(keyword, PER_PLATFORM_FETCH);
            upstreamBreaker.recordSuccess();
            return toDtos(result);
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            recordUpstreamFailure(e);
            log.error("Migu search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    List<SongDTO> safeSearchKugou(String keyword, AtomicBoolean failed) {
        try {
            Map<String, Object> result = neteaseApiService.searchKugou(keyword, PER_PLATFORM_FETCH);
            upstreamBreaker.recordSuccess();
            return toDtos(result);
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            recordUpstreamFailure(e);
            log.error("Kugou search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    List<SongDTO> safeSearchBili(String keyword, AtomicBoolean failed) {
        try {
            Map<String, Object> result = neteaseApiService.searchBili(keyword, PER_PLATFORM_FETCH);
            upstreamBreaker.recordSuccess();
            return toDtos(result);
        } catch (Exception e) {
            if (failed != null) failed.set(true);
            recordUpstreamFailure(e);
            log.error("Bili search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            return Collections.emptyList();
        }
    }

    /** 网关响应体 → DTO 列表：null/data 缺失均为空列表，单条解析失败由 SongDtoParser 过滤。 */
    @SuppressWarnings("unchecked")
    private List<SongDTO> toDtos(Map<String, Object> result) {
        if (result == null) return Collections.emptyList();
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        if (data == null) return Collections.emptyList();
        return data.stream()
                .map(SongDtoParser::parsePlatformSong).filter(Objects::nonNull)
                .peek(SongDtoParser::rewriteHttpCoverUrl)
                .collect(Collectors.toList());
    }

    List<SongDTO> getWithTimeout(Future<List<SongDTO>> future, int seconds, String platform, AtomicBoolean failed) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("{} search timed out after {}s", platform, seconds);
            future.cancel(true);
            if (failed != null) failed.set(true);
        } catch (Exception e) {
            log.error("{} search failed: {}", platform, e.getMessage(), e);
            if (failed != null) failed.set(true);
        }
        return Collections.emptyList();
    }

    /** QQ 专属超时等待：超时/异常计失败（与任务内计数互斥，单次搜索恰好计数一次）。 */
    List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed) {
        return getQqWithTimeout(future, claimed, null);
    }

    /** QQ 专属超时等待：超时/异常计失败（与任务内计数互斥，单次搜索恰好计数一次）。 */
    List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed, AtomicBoolean failed) {
        return getQqWithTimeout(future, claimed, failed, QQ_TIMEOUT_SEC);
    }

    List<SongDTO> getQqWithTimeout(Future<List<SongDTO>> future, AtomicBoolean claimed,
                                   AtomicBoolean failed, int seconds) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("QQ search timed out after {}s", seconds);
            future.cancel(true);
            recordQqOutcomeOnce(claimed, false);
            if (failed != null) failed.set(true);
        } catch (Exception e) {
            log.error("QQ search failed: {}", e.getMessage(), e);
            recordQqOutcomeOnce(claimed, false);
            if (failed != null) failed.set(true);
        }
        return Collections.emptyList();
    }

    /** 剩余预算秒数（至少 1s），供 fanout 全局 deadline 分配。 */
    static int remainingSec(long deadlineMs, int capSec) {
        long remainMs = deadlineMs - System.currentTimeMillis();
        if (remainMs <= 0) return 1;
        return (int) Math.min(capSec, (remainMs + 999) / 1000);
    }
}
