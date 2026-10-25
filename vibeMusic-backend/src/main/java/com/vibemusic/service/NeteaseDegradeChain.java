package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.common.utils.ClientErrorScrubber;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 跨平台降级链（从 SongPlayService 抽出，行为不变）：网易云不可用 → QQ，
 * 以及 QQ/酷狗/B站/咪咕不可用 → 网易云。
 *
 * <p>职责边界：只负责「换一个平台再试一次」，含词重叠匹配与试听片段过滤；
 * 平台选择与 MinIO 缓存仍在 facade。任何上游失败都只返回 null 或原响应，绝不抛错。
 */
@Slf4j
class NeteaseDegradeChain {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final NeteaseUrlProbe neteaseUrlProbe;
    /** 与 facade 共享同一计数器：getDegradationCount 与 Prometheus gauge 都读它。 */
    private final AtomicInteger degradationCount;
    private final ThreadPoolTaskExecutor getUrlExecutor;

    NeteaseDegradeChain(SongMapper songMapper,
                        NeteaseApiService neteaseApiService,
                        NeteaseUrlProbe neteaseUrlProbe,
                        AtomicInteger degradationCount,
                        ThreadPoolTaskExecutor getUrlExecutor) {
        this.songMapper = songMapper;
        this.neteaseApiService = neteaseApiService;
        this.neteaseUrlProbe = neteaseUrlProbe;
        this.degradationCount = degradationCount;
        this.getUrlExecutor = getUrlExecutor;
    }

    /**
     * 网易→QQ 降级链唯一入口（Q2d 收敛）。
     *
     * <p>决策记录：网易云无可用链接（全失败或全试听）时统一经此步到 QQ，再落到 DB
     * 兜底；原先生产路径的 {@code neAllFailed} 门控只在取链超时才成立
     * （supplier 内异常被吞，flag 近似死代码），会导致全试听时跳过 QQ 直接落 DB，
     * 已删除。历史上的孪生元信息路径 getPlayInfo 已整体移除（零生产调用）。
     */
    String degradeNeteaseToQq(String songName, String artist, String sourceId, long deadline) {
        degradationCount.incrementAndGet();
        log.info("音质降级: {} 网易云不可用 → 尝试QQ降级", ClientErrorScrubber.scrub(sourceId));
        return callWithDeadline(() -> tryQQFallback(songName, artist, sourceId), deadline);
    }

    @SuppressWarnings("unchecked")
    private String tryQQFallback(String songName, String artist, String neteaseId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, neteaseId)
                    .select(Song::getName, Song::getArtist));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseApiService.searchQQ(keyword, 5);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            // 歌名/歌手相似度匹配，避免取到错误歌曲
            Map<String, Object> best = SongMatcher.findBestMatch(data, songName, artist);
            if (best == null) { log.info("QQ降级: '{}' 无匹配歌曲", ClientErrorScrubber.scrub(keyword)); return null; }
            String qqSourceId = best.get("id") != null ? String.valueOf(best.get("id")) : null;
            String matchName = best.get("name") != null ? String.valueOf(best.get("name")) : "";
            String matchArtists = best.get("artists") != null ? String.valueOf(best.get("artists")) : "";
            if (qqSourceId == null) return null;
            Object durObj = best.get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= NeteaseUrlProbe.TRIAL_DURATION_MS) {
                log.info("QQ降级: '{}' 也只有试听版，跳过", ClientErrorScrubber.scrub(matchName));
                return null;
            }
            Map<String, Object> urlResult = neteaseApiService.getQQSongUrl(qqSourceId);
            if (urlResult != null) {
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData != null && !urlData.isEmpty()) {
                    String url = (String) urlData.get(0).get("url");
                    if (url != null && !url.isEmpty()) {
                        log.info("QQ降级成功: '{}' → QQ '{}' (score={})", ClientErrorScrubber.scrub(keyword), ClientErrorScrubber.scrub(matchName), SongMatcher.similarity(keyword, matchName + matchArtists));
                        return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ降级搜索失败: {} - {}", ClientErrorScrubber.scrub(songName), ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    String tryNeteaseFallback(String songName, String artist, String qqSourceId, String userCookie, Long userId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>()
                    .eq(Song::getSourceId, qqSourceId)
                    .select(Song::getName, Song::getArtist));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseUrlProbe.fetchNeteaseSearch(keyword, 5, userCookie);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            String neteaseId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (neteaseId == null) return null;
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= NeteaseUrlProbe.TRIAL_DURATION_MS) {
                log.info("网易云降级: {} 也只有试听版，跳过", ClientErrorScrubber.scrub(keyword));
                return null;
            }
            String[] levels = {"exhigh", "higher", "standard"};
            for (String level : levels) {
                Map<String, Object> urlResult = neteaseUrlProbe.fetchNeteaseUrl(neteaseId, level, userCookie, userId);
                if (urlResult == null) continue;
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData == null || urlData.isEmpty()) continue;
                String url = (String) urlData.get(0).get("url");
                if (url == null || url.isEmpty()) continue;
                Object trial = urlData.get(0).get("freeTrialInfo");
                Object time = urlData.get(0).get("time");
                if (trial != null || (time instanceof Number && ((Number) time).intValue() <= NeteaseUrlProbe.TRIAL_DURATION_MS)) continue;
                log.info("网易云降级成功: {} → neteaseId={}, level={}", ClientErrorScrubber.scrub(keyword), neteaseId, level);
                return url;
            }
            log.info("网易云降级: {} 所有音质均为试听，跳过", ClientErrorScrubber.scrub(keyword));
        } catch (Exception e) {
            log.warn("网易云降级搜索失败: {} - {}", ClientErrorScrubber.scrub(songName), ClientErrorScrubber.scrub(e.getMessage()), e);
        }
        return null;
    }

    /**
     * 在剩余预算内限时执行上游调用：预算约束调用本身而非仅约束是否发起。
     * 预算耗尽/超时/异常一律返回 null，由调用方按降级处理；超时时 cancel 排队任务。
     */
    private <T> T callWithDeadline(Supplier<T> s, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            log.warn("限时调用跳过: 预算已耗尽");
            return null;
        }
        final CompletableFuture<T> cf;
        try {
            cf = CompletableFuture.supplyAsync(s, getUrlExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("限时调用取链池过载，直接跳过");
            return null;
        }
        try {
            return cf.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cf.cancel(true);
            log.warn("限时调用超时: {}ms 预算内未返回，已取消", left);
            return null;
        } catch (InterruptedException e) {
            cf.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("限时调用被中断，已取消");
            return null;
        } catch (ExecutionException e) {
            // CompletableFuture 把上游异常包在 ExecutionException 里：只打外层 message 会丢掉真实堆栈
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("限时调用上游异常: {}", ClientErrorScrubber.scrub(cause.getMessage()), cause);
            return null;
        }
    }
}
