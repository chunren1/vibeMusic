package com.vibemusic.controller;

import com.vibemusic.common.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存命中率与上游健康仪表盘。
 *
 * <p>只做「读埋点 → 拼装 JSON」，不自己算业务指标：所有计数/计时器都由
 * {@code SongSearchService}、{@code NeteaseUrlProbe} 在真实调用路径上埋好，
 * 这里不做推算，避免看板与真实行为脱节。</p>
 *
 * <p><b>2026-10-25 语义修正</b>：旧实现把 {@code cache_hit_rate}、{@code redis_hit_rate}、
 * {@code api_saved_rate} 三行写成同一个表达式（{@code redisHits / total}），
 * 看板上的「API 节省率」是假的。现改为：</p>
 * <ul>
 *   <li>{@code cache_hit_rate} —— 唯一的比率字段（Redis 命中 / 全部缓存查找）；</li>
 *   <li>{@code api_saved_calls} —— 「因缓存省掉的上游调用」绝对次数，不再伪装成百分比；</li>
 *   <li>补齐此前埋了但没暴露的计数：线程池拒绝、五平台全失败、网易需要登录、Cookie 轮换；</li>
 *   <li>{@code qq_circuit_open} —— QQ 熔断是否开路（Gauge {@code circuit.qq.open}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "监控", description = "缓存命中率与上游健康仪表盘")
public class CacheMonitorController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/cache-stats")
    @Operation(summary = "缓存命中率与上游健康统计")
    public Result<Map<String, Object>> cacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        double redisHits = counter("cache.hit.redis");
        double upstreamCalls = counter("cache.miss.api");
        double lookups = redisHits + upstreamCalls;

        stats.put("redis_hits", (long) redisHits);
        stats.put("upstream_calls", (long) upstreamCalls);
        stats.put("total_lookups", (long) lookups);
        stats.put("cache_hit_rate", lookups > 0
                ? String.format("%.1f%%", redisHits / lookups * 100)
                : "N/A (无查询)");
        stats.put("api_saved_calls", (long) redisHits);

        stats.put("avg_search_latency_ms", String.format("%.1f", timerMean("search.latency")));
        stats.put("search_pool_rejected", (long) counter("search.pool.rejected"));
        stats.put("search_upstream_all_failed", (long) counter("search.upstream.all_failed"));

        stats.put("netease_need_login", (long) counter("play.netease.need_login"));
        stats.put("netease_cookie_rotate", (long) counter("play.netease.cookie_rotate"));

        stats.put("qq_circuit_open", gauge("circuit.qq.open") >= 1.0);

        return Result.ok(stats);
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c != null ? c.count() : 0;
    }

    private double gauge(String name) {
        var g = meterRegistry.find(name).gauge();
        return g != null ? g.value() : 0;
    }

    private double timerMean(String name) {
        var t = meterRegistry.find(name).timer();
        return t != null ? t.mean(TimeUnit.MILLISECONDS) : 0;
    }
}
