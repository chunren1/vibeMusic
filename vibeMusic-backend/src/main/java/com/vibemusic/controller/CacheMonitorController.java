package com.vibemusic.controller;

import com.vibemusic.common.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 缓存命中率仪表盘
 *
 * 暴露 Micrometer 埋点数据，可在 Grafana 或简单日志面板中展示。
 * 面试亮点：「通过全链路埋点，实时看到三级缓存策略节省了多少 API 调用量」
 */
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "监控", description = "缓存命中率仪表盘")
public class CacheMonitorController {

    private final MeterRegistry meterRegistry;

    @GetMapping("/cache-stats")
    @Operation(summary = "缓存命中率统计")
    public Result<Map<String, Object>> cacheStats() {
        Map<String, Object> stats = new HashMap<>();

        double redisHits = getCounter("cache.hit.redis");
        double esHits = getCounter("cache.hit.es");
        double apiMisses = getCounter("cache.miss.api");
        double total = redisHits + esHits + apiMisses;

        stats.put("redis_hits", (long) redisHits);
        stats.put("es_hits", (long) esHits);
        stats.put("api_misses", (long) apiMisses);
        stats.put("total_requests", (long) total);

        if (total > 0) {
            stats.put("cache_hit_rate", String.format("%.1f%%", (redisHits + esHits) / total * 100));
            stats.put("redis_hit_rate", String.format("%.1f%%", redisHits / total * 100));
            stats.put("es_hit_rate", String.format("%.1f%%", esHits / total * 100));
            stats.put("api_saved_rate", String.format("%.1f%%", (redisHits + esHits) / total * 100));
        } else {
            stats.put("cache_hit_rate", "N/A (无查询)");
            stats.put("api_saved_rate", "N/A (无查询)");
        }

        double avgLatency = getTimerMean("search.latency");
        stats.put("avg_search_latency_ms", String.format("%.1f", avgLatency));

        return Result.ok(stats);
    }

    private double getCounter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0;
    }

    private double getTimerMean(String name) {
        var timer = meterRegistry.find(name).timer();
        return timer != null ? timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS) : 0;
    }
}
