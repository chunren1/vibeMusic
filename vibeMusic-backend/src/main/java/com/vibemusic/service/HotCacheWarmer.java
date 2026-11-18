package com.vibemusic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.mapper.PlayHistoryMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 热歌缓存预热：每天凌晨把「最近常听」的歌拉到本地磁盘。
 *
 * <p><b>真实性是硬门槛</b>（2026-11-17 用户明确要求）：缓存里的每一首都要能证明
 * 「这是官方音源的那一首」，宁可少缓存也不能让用户听到 DJ 版/翻唱/剪辑版。四道闸：</p>
 * <ol>
 *   <li><b>官方平台</b>：只接受网易云与 QQ 音乐。酷狗/咪咕/B站的音频一律不落盘
 *       ——B站是视频轨二创最多的地方，直接排除。</li>
 *   <li><b>码率下限</b>：{@code qualityRank >= 3}（320k / HQ / FLAC）。unknown 与 128/AAC
 *       全部拒绝：unknown 意味着取链层自己都不确定给的是哪一档。</li>
 *   <li><b>时长校验</b>：先用网易云曲库搜同名同歌手拿到权威时长，候选时长必须落在
 *       {@code max(5s, 3%)} 内。DJ 版、加速版、剪辑版在时长上一定对不上；
 *       拿不到权威时长就整首跳过——没有基准就没有真实性。</li>
 *   <li><b>精确下载</b>：以 {@code exact=true} 只取解析时承诺的那一条，不让换源在下载时
 *       再挑一次（否则会出现「解析是 A、下载成 B」）。</li>
 * </ol>
 *
 * <p>为什么走自己的 {@code /api/songs/stream} 而不是直接下载直链：Referer/UA、重试、
 * -460 短路全在 {@code AudioProxyService} 里，绕开它等于把这套逻辑抄一遍。</p>
 *
 * <p>预算控制：每轮最多 {@code warm-limit} 首、每首间隔 {@code warm-gap-ms}，
 * 容量不足（{@link HotCacheService#hasRoomFor}）立即停止。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotCacheWarmer {

    /** 单曲典型体积：320k × 4 分钟 ≈ 9.6MB，用它做「还放得下吗」的粗判。 */
    private static final long TYPICAL_SONG_BYTES = 10L * 1024L * 1024L;

    /**
     * 只缓存官方音源：网易云、QQ 音乐、咪咕。
     *
     * <p>咪咕 2026-11-18 经用户确认加入：它同样是正版唱片库，只是不在「网易云/QQ」两家里。
     * 酷狗与 B站 仍然排除——前者音质档位不透明（standard 可能悄悄回落 128），
     * 后者是视频轨二创，翻唱与剪辑比例最高。</p>
     */
    static final Set<String> OFFICIAL_PLATFORMS = Set.of("netease", "qq", "migu");

    /** 最低可接受码率档（320k / HQ / FLAC 记为 3）。 */
    static final int MIN_QUALITY_RANK = 3;

    private final HotCacheService hotCache;
    private final PlayHistoryMapper playHistoryMapper;
    private final SongPlayService songPlayService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${hot-cache.self-base:http://127.0.0.1:8080}")
    private String selfBase;

    @Value("${hot-cache.warm-limit:60}")
    private int warmLimit;

    @Value("${hot-cache.warm-days:60}")
    private int warmDays;

    @Value("${hot-cache.warm-gap-ms:3000}")
    private long gapMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hot-cache-warmer");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 预热结果，仅用于日志与测试断言。 */
    public record Report(int stored, int skipped, int failed, String stoppedBy) {
    }

    /** 每天 03:30。刻意用固定 cron：批量下载不该撞上白天播放。 */
    @Scheduled(cron = "${hot-cache.warm-cron:0 30 3 * * ?}")
    public void scheduledWarm() {
        if (!hotCache.isEnabled()) return;
        executor.submit(() -> {
            Report r = warm();
            log.info("热歌缓存预热完成: 新增 {} / 已缓存 {} / 失败 {} / 停止原因 {}",
                    r.stored(), r.skipped(), r.failed(), r.stoppedBy());
        });
    }

    /**
     * 启动时预热：缓存目录为空时自动跑一轮，避免「刚上线怎么没变快」这类无谓排查。
     *
     * <p>另外支持手工触发：在缓存目录放一个 {@code .warm-now} 空文件再重启容器，
     * 即使已有缓存也会立刻重跑一轮（用完即删）。白名单/规则调整后想马上看到效果，
     * 不必等到凌晨 03:30，也不必为此开一个 HTTP 管理端点。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnFirstBoot() {
        if (!hotCache.isEnabled()) return;
        executor.submit(() -> {
            Path sentinel = hotCache.root().resolve(".warm-now");
            boolean forced = Files.isRegularFile(sentinel);
            if (hotCache.usedBytes() > 0L && !forced) return;
            try {
                Files.deleteIfExists(sentinel);
            } catch (Exception ignored) {
                // 哨兵删不掉最多导致下次重启再跑一轮，不影响正确性
            }
            Report r = warm();
            log.info("首次启动空缓存，预热结果: 新增 {} / 失败 {} / 停止原因 {}",
                    r.stored(), r.failed(), r.stoppedBy());
        });
    }

    /** 同步执行一轮预热（测试直接调它）。 */
    public Report warm() {
        if (!running.compareAndSet(false, true)) {
            return new Report(0, 0, 0, "已有一轮在跑");
        }
        int stored = 0, skipped = 0, failed = 0;
        String stoppedBy = "跑完";
        try {
            List<Map<String, Object>> rows = playHistoryMapper.selectHotSongs(warmDays, warmLimit);
            log.info("热歌缓存预热开始: 候选 {} 首（近 {} 天）", rows.size(), warmDays);
            for (Map<String, Object> row : rows) {
                String sourceId = str(row.get("sourceId"));
                if (sourceId.isBlank()) continue;
                if (!hotCache.hasRoomFor(TYPICAL_SONG_BYTES)) {
                    stoppedBy = "容量或磁盘余量不足";
                    break;
                }
                if (hotCache.lookup(sourceId) != null) {
                    skipped++;
                    continue;
                }
                if (warmOne(sourceId, str(row.get("songName")), str(row.get("artist")))) {
                    stored++;
                } else {
                    failed++;
                }
                sleepQuietly(gapMs);
            }
        } catch (Exception e) {
            stoppedBy = "异常: " + e.getMessage();
            log.warn("热歌缓存预热中断", e);
        } finally {
            running.set(false);
        }
        return new Report(stored, skipped, failed, stoppedBy);
    }

    /** 单曲全流程：权威时长 → 解析 → 四道闸 → 精确下载 → 写元信息。 */
    boolean warmOne(String sourceId, String name, String artist) {
        Long refDuration = songPlayService.referenceDurationMs(name, artist);
        if (refDuration == null || refDuration <= 0L) {
            log.info("热歌缓存跳过 {}「{}」: 拿不到权威时长，无法确认真实性", sourceId, name);
            return false;
        }
        SongPlayService.PlayResolution res =
                songPlayService.resolve(sourceId, name, artist, null, "", refDuration);
        if (!res.hasUrl()) {
            log.info("热歌缓存跳过 {}「{}」: 无可用音源", sourceId, name);
            return false;
        }
        if (!OFFICIAL_PLATFORMS.contains(String.valueOf(res.platform()).toLowerCase())) {
            log.info("热歌缓存跳过 {}「{}」: 命中平台 {} 非官方音源", sourceId, name, res.platform());
            return false;
        }
        if (CrossPlatformFailover.qualityRank(res.quality()) < MIN_QUALITY_RANK) {
            log.info("热歌缓存跳过 {}「{}」: 音质 {} 低于 320k", sourceId, name, res.quality());
            return false;
        }
        if (!durationCompatible(refDuration, res.durationMs())) {
            log.info("热歌缓存跳过 {}「{}」: 时长不符（权威 {}ms / 候选 {}ms），疑似非原版",
                    sourceId, name, refDuration, res.durationMs());
            return false;
        }
        if (!downloadExact(sourceId, res)) return false;
        try {
            writeMeta(sourceId, res);
        } catch (Exception e) {
            log.warn("热歌缓存元信息写入失败 {}: {}", sourceId, e.getMessage());
        }
        return true;
    }

    /** 与 {@code SongMatcher} 同口径：max(5s, 3%)。候选时长未知一律判不符。 */
    static boolean durationCompatible(long expectedMs, Long candidateMs) {
        if (candidateMs == null || candidateMs <= 0L) return false;
        long tolerance = Math.max(5_000L, Math.round(expectedMs * 0.03d));
        return Math.abs(candidateMs - expectedMs) <= tolerance;
    }

    /** 用解析结果里的 platform + sourceId 走 exact=true 下载，保证拿到的是解析承诺的那一条。 */
    private boolean downloadExact(String originalSourceId, SongPlayService.PlayResolution res) {
        try {
            String query = "sourceId=" + enc(res.sourceId())
                    + "&platform=" + enc(res.platform())
                    + "&exact=true";
            URI uri = URI.create(selfBase + "/api/songs/stream?" + query);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    log.info("热歌缓存下载失败 {}: stream 返回 {}", originalSourceId, resp.statusCode());
                    return false;
                }
                String contentType = resp.headers().firstValue("Content-Type").orElse("");
                if (!contentType.startsWith("audio/")) {
                    log.info("热歌缓存跳过 {}: Content-Type={}", originalSourceId, contentType);
                    return false;
                }
                long length = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                return hotCache.store(originalSourceId, in, length);
            }
        } catch (Exception e) {
            log.info("热歌缓存下载异常 {}: {}", originalSourceId, e.getMessage());
            return false;
        }
    }

    /** 旁挂元信息：播放时据此还原「这是替代版本」的提示。 */
    private void writeMeta(String sourceId, SongPlayService.PlayResolution res) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("isSubstitute", res.isSubstitute());
        meta.put("platform", res.platform());
        meta.put("title", res.name());
        meta.put("artist", res.artists());
        meta.put("coverUrl", res.coverUrl());
        meta.put("reason", res.reason());
        meta.put("quality", res.quality());
        meta.put("durationMs", res.durationMs());
        Path metaPath = hotCache.metaPath(sourceId);
        Files.writeString(metaPath, objectMapper.writeValueAsString(meta), StandardCharsets.UTF_8);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
