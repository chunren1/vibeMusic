package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 歌曲搜索服务
 * <p>
 * 三级缓存：Redis → ES → API 实时搜索
 * 支持分源搜索（网易云 / QQ）和跨平台去重合并
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongSearchService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;
    private final ESSearchService esSearchService;

    private static final double NET_WEIGHT = 1.0;
    private static final double QQ_WEIGHT = 0.9;
    private static final double CROSS_PLATFORM_BONUS = 0.3;
    private static final int PER_PLATFORM_FETCH = 40;
    private static final int SEARCH_TIMEOUT_SEC = 4;
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

    /** 共享搜索线程池（避免每次搜索创建/销毁） */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "search-worker");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        SEARCH_EXECUTOR.shutdown();
    }

    @SuppressWarnings("unchecked")
    public List<SongDTO> search(String keyword, int page, int size) {
        return search(keyword, page, size, null);
    }

    /**
     * 搜索歌曲（支持分源）
     * @param platform 可选: "netease", "qq", null=全部
     */
    @SuppressWarnings("unchecked")
    public List<SongDTO> search(String keyword, int page, int size, String platform) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        final String kw = keyword.trim();
        final boolean searchBoth = (platform == null || platform.trim().isEmpty());
        final long searchStart = System.currentTimeMillis();

        String cacheExtra = searchBoth ? "all" : platform.trim().toLowerCase();

        // ======== 第 1 步：Redis 缓存 ========
        long redisStart = System.currentTimeMillis();
        List<SongDTO> cached = cacheService.getSearchCache(kw + ":" + cacheExtra, page);
        if (cached != null && !cached.isEmpty()) {
            List<SongDTO> all = cacheService.getSearchCache(kw + ":" + cacheExtra, 1);
            if (all != null && !all.isEmpty()) {
                long redisCost = System.currentTimeMillis() - redisStart;
                log.info("[CACHE-LAYER] Redis 命中: keyword='{}', page={}, totalCost={}ms",
                        kw, page, redisCost);
                int from = (page - 1) * size;
                int to = Math.min(from + size, all.size());
                if (from >= all.size()) return Collections.emptyList();
                return all.subList(from, to);
            }
        }
        long redisCost = System.currentTimeMillis() - redisStart;
        log.info("[CACHE-LAYER] Redis 未命中: keyword='{}', page={}, cost={}ms", kw, page, redisCost);

        // ======== 第 2 步：ES 缓存（仅 :all 模式） ========
        if (searchBoth) {
            long esStart = System.currentTimeMillis();
            List<SongDTO> esCached = esSearchService.findByKeyword(kw);
            if (!esCached.isEmpty()) {
                long esCost = System.currentTimeMillis() - esStart;
                log.info("[ES-LAYER] 返回ES缓存结果: keyword='{}', count={}, ES-cost={}ms, totalCost={}ms",
                        kw, esCached.size(), esCost, System.currentTimeMillis() - searchStart);
                cacheService.setSearchCache(kw + ":all", page, esCached, true, false);
                int from = (page - 1) * size;
                int to = Math.min(from + size, esCached.size());
                if (from >= esCached.size()) return Collections.emptyList();
                return esCached.subList(from, to);
            }
        }

        // ======== 第 3 步：API 实时搜索（兜底） ========
        log.info("[API-LAYER] 触发实时搜索: keyword='{}', 原因=Redis和ES均未命中", kw);
        long apiStart = System.currentTimeMillis();

        if ("netease".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchNetease(kw));
            for (SongDTO s : songs) s.setPlatform("netease");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":netease", 1, songs, true);
            int from = (page - 1) * size;
            int to = Math.min(from + size, songs.size());
            if (from >= songs.size()) return Collections.emptyList();
            return songs.subList(from, to);
        }
        if ("qq".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchQQ(kw));
            for (SongDTO s : songs) s.setPlatform("qq");
            if (!songs.isEmpty()) cacheService.setSearchCache(kw + ":qq", 1, songs, true);
            int from = (page - 1) * size;
            int to = Math.min(from + size, songs.size());
            if (from >= songs.size()) return Collections.emptyList();
            return songs.subList(from, to);
        }

        // 合并搜索（复用共享线程池）
        Future<List<SongDTO>> neF = SEARCH_EXECUTOR.submit(() -> safeSearchNetease(kw));
        Future<List<SongDTO>> qqF = SEARCH_EXECUTOR.submit(() -> safeSearchQQ(kw));
        List<SongDTO> neteaseSongs = getWithTimeout(neF, SEARCH_TIMEOUT_SEC, "Netease");
        List<SongDTO> qqSongs = getWithTimeout(qqF, SEARCH_TIMEOUT_SEC, "QQ");

        Map<String, SongDTO> mergedMap = new LinkedHashMap<>();

        for (int i = 0; i < neteaseSongs.size(); i++) {
            SongDTO song = neteaseSongs.get(i);
            song.setPlatform("netease");
            song.setAvailableSources(new ArrayList<>(List.of("netease")));
            song.setFinalScore(computeScore(i + 1, NET_WEIGHT));
            String key = normalizeKey(song.getName(), song.getArtist());
            mergedMap.put(key, song);
        }

        for (int i = 0; i < qqSongs.size(); i++) {
            SongDTO song = qqSongs.get(i);
            song.setPlatform("qq");
            song.setAvailableSources(new ArrayList<>(List.of("qq")));
            song.setFinalScore(computeScore(i + 1, QQ_WEIGHT));
            String key = normalizeKey(song.getName(), song.getArtist());
            SongDTO existing = mergedMap.get(key);

            if (existing != null) {
                List<String> allSources = new ArrayList<>(existing.getAvailableSources());
                if (!allSources.contains("qq")) allSources.add("qq");
                SongDTO winner = pickBest(existing, song);
                winner.setAvailableSources(allSources);
                winner.setFinalScore(Math.max(existing.getFinalScore(), song.getFinalScore()) + CROSS_PLATFORM_BONUS);
                mergedMap.put(key, winner);
            } else {
                mergedMap.put(key, song);
            }
        }

        List<SongDTO> resultList = new ArrayList<>(mergedMap.values());
        resultList.sort((a, b) -> Double.compare(
                b.getFinalScore() != null ? b.getFinalScore() : 0,
                a.getFinalScore() != null ? a.getFinalScore() : 0));

        long apiCost = System.currentTimeMillis() - apiStart;
        long totalCost = System.currentTimeMillis() - searchStart;
        log.info("[API-LAYER] 搜索完成: keyword='{}', 网易云={}首, QQ={}首, 去重后={}首, API-cost={}ms, totalCost={}ms",
                kw, neteaseSongs.size(), qqSongs.size(), resultList.size(), apiCost, totalCost);

        boolean incomplete = neteaseSongs.isEmpty() || qqSongs.isEmpty();
        cacheService.setSearchCache(kw + ":all", page, resultList, !resultList.isEmpty(), incomplete);

        if (!resultList.isEmpty()) {
            esSearchService.indexSearchResults(kw, resultList);
        }

        int from = (page - 1) * size;
        int to = Math.min(from + size, resultList.size());
        if (from >= resultList.size()) return Collections.emptyList();
        return resultList.subList(from, to);
    }

    public List<SongDTO> search(String keyword) {
        return search(keyword, 1, 20);
    }

    public List<SongDTO> getRandomSongs(int count) {
        List<SongDTO> songs = search("热歌", 1, 30, null);
        songs = songs.stream()
                .filter(s -> s.getSourceId() != null && !s.getSourceId().isEmpty())
                .filter(s -> s.getDuration() == null || s.getDuration() > 30)
                .collect(Collectors.toList());
        Collections.shuffle(songs);
        if (songs.size() > count) return songs.subList(0, count);
        if (songs.size() < count) {
            List<Song> dbSongs = songMapper.findRandomSongs(count - songs.size());
            List<SongDTO> dbDtos = dbSongs.stream().map(s -> SongDTO.builder()
                    .sourceId(s.getSourceId()).name(s.getName()).artist(s.getArtist())
                    .album(s.getAlbum()).coverUrl(s.getCoverUrl()).duration(s.getDuration()).build()
            ).collect(Collectors.toList());
            songs.addAll(dbDtos);
        }
        return songs;
    }

    // ==================== 私有辅助方法 ====================

    private double computeScore(int rank, double platformWeight) {
        return (1.0 / rank) * platformWeight;
    }

    private SongDTO pickBest(SongDTO a, SongDTO b) {
        boolean aTrial = a.getDuration() != null && a.getDuration() <= 30;
        boolean bTrial = b.getDuration() != null && b.getDuration() <= 30;
        if (aTrial && !bTrial) return b;
        if (!aTrial && bTrial) return a;
        double aQ = qualityScore(a);
        double bQ = qualityScore(b);
        if (aQ != bQ) return aQ > bQ ? a : b;
        double aS = a.getFinalScore() != null ? a.getFinalScore() : 0;
        double bS = b.getFinalScore() != null ? b.getFinalScore() : 0;
        return aS >= bS ? a : b;
    }

    private double qualityScore(SongDTO song) {
        double score = 0;
        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) score += 0.5;
        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) score += 0.5;
        return score;
    }

    private String normalizeKey(String name, String artist) {
        String raw = (name != null ? name : "") + "|" + (artist != null ? artist : "");
        return NON_ALPHANUM.matcher(raw.toLowerCase().replaceAll("\\s+", "")).replaceAll("");
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchNetease(String keyword) {
        try {
            Map<String, Object> result = neteaseApiService.searchNetease(keyword, PER_PLATFORM_FETCH);
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return Collections.emptyList();
            return data.stream()
                    .map(this::parsePlatformSong).filter(Objects::nonNull)
                    .peek(this::rewriteHttpCoverUrl)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Netease search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SongDTO> safeSearchQQ(String keyword) {
        try {
            Map<String, Object> result = neteaseApiService.searchQQ(keyword, PER_PLATFORM_FETCH);
            if (result == null) return Collections.emptyList();
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null) return Collections.emptyList();
            return data.stream()
                    .map(this::parsePlatformSong).filter(Objects::nonNull)
                    .peek(this::rewriteHttpCoverUrl)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private void rewriteHttpCoverUrl(SongDTO song) {
        if (song.getCoverUrl() != null && song.getCoverUrl().startsWith("http://")) {
            String encoded = java.net.URLEncoder.encode(song.getCoverUrl(), java.nio.charset.StandardCharsets.UTF_8);
            song.setCoverUrl("/api/image-proxy?url=" + encoded);
        }
    }

    private SongDTO parsePlatformSong(Map<String, Object> raw) {
        try {
            String sourceId = raw.get("id") != null ? String.valueOf(raw.get("id")) : "";
            if (sourceId.isEmpty()) return null;
            String name = raw.get("name") != null ? String.valueOf(raw.get("name")) : "";
            String artists = raw.get("artists") != null ? String.valueOf(raw.get("artists")) : "未知歌手";
            String album = raw.get("album") != null ? String.valueOf(raw.get("album")) : "";
            String coverUrl = raw.get("cover") != null ? String.valueOf(raw.get("cover")) : "";
            int duration = 0;
            Object durObj = raw.get("duration");
            if (durObj instanceof Number) duration = ((Number) durObj).intValue() / 1000;
            Object vipObj = raw.get("vip");
            boolean vip = vipObj instanceof Boolean ? (Boolean) vipObj : false;

            SongDTO dto = new SongDTO();
            dto.setSourceId(sourceId); dto.setName(name); dto.setArtist(artists);
            dto.setAlbum(album); dto.setCoverUrl(coverUrl); dto.setDuration(duration);
            dto.setVip(vip);
            return dto;
        } catch (Exception e) {
            log.warn("Failed to parse platform song: {}", e.getMessage());
            return null;
        }
    }

    private <T> List<T> getWithTimeout(Future<List<T>> future, int seconds, String platform) {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) { log.warn("{} search timed out after {}s", platform, seconds); future.cancel(true); }
        catch (Exception e) { log.error("{} search failed: {}", platform, e.getMessage()); }
        return Collections.emptyList();
    }
}
