package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SongService {

    private final SongMapper songMapper;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;
    private final StorageService storageService;

    private static final double NET_WEIGHT = 1.0;
    private static final double QQ_WEIGHT = 0.9;
    private static final double CROSS_PLATFORM_BONUS = 0.3;
    private static final int PER_PLATFORM_FETCH = 40;
    private static final int SEARCH_TIMEOUT_SEC = 4;
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

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

        String cacheExtra = searchBoth ? "all" : platform.trim().toLowerCase();
        List<SongDTO> cached = cacheService.getSearchCache(kw + ":" + cacheExtra, page);
        if (cached != null && !cached.isEmpty()) {
            List<SongDTO> all = cacheService.getSearchCache(kw + ":" + cacheExtra, 1);
            if (all != null && !all.isEmpty()) {
                int from = (page - 1) * size;
                int to = Math.min(from + size, all.size());
                if (from >= all.size()) return Collections.emptyList();
                return all.subList(from, to);
            }
        }

        log.info("Search '{}' platform={} page={} Redis miss", kw, cacheExtra, page);

        // 只搜指定平台
        if ("netease".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchNetease(kw));
            for (SongDTO s : songs) s.setPlatform("netease");
            cacheService.setSearchCache(kw + ":netease", page, songs, !songs.isEmpty());
            return songs;
        }
        if ("qq".equals(cacheExtra)) {
            List<SongDTO> songs = new ArrayList<>(safeSearchQQ(kw));
            for (SongDTO s : songs) s.setPlatform("qq");
            cacheService.setSearchCache(kw + ":qq", page, songs, !songs.isEmpty());
            return songs;
        }

        // 合并搜索
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<List<SongDTO>> neF = executor.submit(() -> safeSearchNetease(kw));
        Future<List<SongDTO>> qqF = executor.submit(() -> safeSearchQQ(kw));
        List<SongDTO> neteaseSongs = getWithTimeout(neF, SEARCH_TIMEOUT_SEC, "Netease");
        List<SongDTO> qqSongs = getWithTimeout(qqF, SEARCH_TIMEOUT_SEC, "QQ");
        executor.shutdownNow();

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

        cacheService.setSearchCache(kw + ":all", page, resultList, !resultList.isEmpty());

        int from = (page - 1) * size;
        int to = Math.min(from + size, resultList.size());
        if (from >= resultList.size()) return Collections.emptyList();
        return resultList.subList(from, to);
    }

    public List<SongDTO> search(String keyword) {
        return search(keyword, 1, 20);
    }

    /** 计算综合评分 = 排名分 * 平台权重 + 质量加分 */
    private double computeScore(int rank, double platformWeight) {
        return (1.0 / rank) * platformWeight;
    }

    /**
     * 从两个同名歌曲中选出最优源：
     * 1. 排除试听版（≤30秒）
     * 2. 对比质量分（有专辑+封面=更可能是正式发行版而非伴奏/翻录）
     * 3. 质量相同时，选排名更靠前的
     */
    private SongDTO pickBest(SongDTO a, SongDTO b) {
        boolean aTrial = a.getDuration() != null && a.getDuration() <= 30;
        boolean bTrial = b.getDuration() != null && b.getDuration() <= 30;

        // 一方是试听，另一方不是 → 选非试听
        if (aTrial && !bTrial) return b;
        if (!aTrial && bTrial) return a;

        // 质量分对比
        double aQ = qualityScore(a);
        double bQ = qualityScore(b);
        if (aQ != bQ) return aQ > bQ ? a : b;

        // 评分对比
        double aS = a.getFinalScore() != null ? a.getFinalScore() : 0;
        double bS = b.getFinalScore() != null ? b.getFinalScore() : 0;
        return aS >= bS ? a : b;
    }

    /** 质量打分：有专辑=0.5分，有封面=0.5分。缺这些通常意味着伴奏/翻录/低质量 */
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
            return data.stream().map(this::parsePlatformSong).filter(Objects::nonNull).collect(Collectors.toList());
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
            return data.stream().map(this::parsePlatformSong).filter(Objects::nonNull).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("QQ search failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
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
            SongDTO dto = new SongDTO();
            dto.setSourceId(sourceId); dto.setName(name); dto.setArtist(artists);
            dto.setAlbum(album); dto.setCoverUrl(coverUrl); dto.setDuration(duration);
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

    private SongDTO parseToDTO(Map<String, Object> raw) { return null; }
    private SongDTO parseAggregatedToDTO(Map<String, Object> raw) { return null; }

    /**
     * 获取播放信息（含试听标记和平台）
     * 优先级：RustFS缓存 → 原平台API → QQ音乐降级(网易云试听时) → DB历史URL
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId) {
        return getPlayInfo(sourceId, null, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlayInfo(String sourceId, String songName, String artist) {
        Map<String, Object> info = new HashMap<>();
        info.put("isTrial", false);
        info.put("platform", sourceId.matches("\\d+") ? "netease" : "qq");

        // 1. 优先检查 RustFS 缓存（已下载的歌曲直接用，无需调API）
        String rustfsObjectName = "songs/" + sourceId + ".mp3";
        if (storageService.exists(rustfsObjectName)) {
            String directUrl = storageService.getDirectUrl(rustfsObjectName);
            info.put("url", directUrl);
            info.put("fromCache", true);
            log.info("歌曲 {} 命中RustFS缓存，直接返回", sourceId);
            return info;
        }

        // 2. 尝试从 API 获取
        try {
            if (sourceId.matches("\\d+")) {
                String[] levels = {"exhigh", "higher", "standard"};
                for (String level : levels) {
                    Map<String, Object> result = neteaseApiService.getSongUrl(sourceId, level);
                    if (result == null) continue;
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data == null || data.isEmpty()) continue;
                    String url = (String) data.get(0).get("url");
                    if (url == null || url.isEmpty()) continue;
                    Object trial = data.get(0).get("freeTrialInfo");
                    Object time = data.get(0).get("time");
                    if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) {
                        log.info("歌曲 {} 音质 {} 为试听, 尝试降级", sourceId, level);
                        continue;
                    }
                    info.put("url", url);
                    return info;
                }
                // 所有音质都是试听 → 尝试 QQ 音乐降级
                log.info("歌曲 {} 所有网易云音质均为试听，尝试QQ降级", sourceId);
                String qqUrl = tryQQFallback(songName, artist, sourceId);
                if (qqUrl != null) {
                    info.put("url", qqUrl);
                    info.put("platform", "qq");
                    info.put("fallbackFrom", "netease-trial");
                    return info;
                }
                // QQ 也没找到，返回试听版
                info.put("isTrial", true);
                Map<String, Object> f = neteaseApiService.getSongUrl(sourceId, "standard");
                if (f != null) {
                    List<Map<String, Object>> d = (List<Map<String, Object>>) f.get("data");
                    if (d != null && !d.isEmpty()) info.put("url", d.get(0).get("url"));
                }
            } else {
                // QQ 音乐 → 尝试获取 URL
                Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String qqUrl = (String) data.get(0).get("url");
                        if (qqUrl != null && !qqUrl.isEmpty()) {
                            info.put("url", qqUrl);
                            return info;
                        }
                    }
                }
                // QQ 音乐获取失败/无URL → 尝试网易云降级
                log.info("QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId);
                if (neteaseUrl != null) {
                    info.put("url", neteaseUrl);
                    info.put("platform", "netease");
                    info.put("fallbackFrom", "qq-no-url");
                    return info;
                }
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试RustFS兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 历史URL兜底
        if (info.get("url") == null) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
            if (song != null && song.getUrl() != null) {
                info.put("url", song.getUrl());
                info.put("fromCache", true);
            }
        }
        return info;
    }

    /**
     * QQ 音乐降级：当网易云返回试听时，搜索同名歌曲从 QQ 获取完整版
     */
    @SuppressWarnings("unchecked")
    private String tryQQFallback(String songName, String artist, String neteaseId) {
        if (songName == null || songName.isBlank()) {
            // 尝试从 DB 查歌名
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, neteaseId));
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
            // 取第一个结果获取 QQ sourceId
            String qqSourceId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (qqSourceId == null) return null;
            // 检查时长，避免又拿到试听版
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= 30000) {
                log.info("QQ降级: {} 也只有试听版，跳过", keyword);
                return null;
            }
            Map<String, Object> urlResult = neteaseApiService.getQQSongUrl(qqSourceId);
            if (urlResult != null) {
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData != null && !urlData.isEmpty()) {
                    String url = (String) urlData.get(0).get("url");
                    if (url != null && !url.isEmpty()) {
                        log.info("QQ降级成功: {} → QQ sourceId={}", keyword, qqSourceId);
                        return url;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("QQ降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }

    /**
     * 网易云降级：当 QQ 音乐无播放链接时，搜索同名歌曲从网易云获取
     */
    @SuppressWarnings("unchecked")
    private String tryNeteaseFallback(String songName, String artist, String qqSourceId) {
        if (songName == null || songName.isBlank()) {
            Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, qqSourceId));
            if (song == null || song.getName() == null) return null;
            songName = song.getName();
            artist = song.getArtist();
        }
        try {
            String keyword = artist != null && !artist.isBlank() ? songName + " " + artist : songName;
            Map<String, Object> result = neteaseApiService.searchNetease(keyword, 5);
            if (result == null) return null;
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data == null || data.isEmpty()) return null;
            // 取第一个结果获取网易云 sourceId
            String neteaseId = data.get(0).get("id") != null ? String.valueOf(data.get(0).get("id")) : null;
            if (neteaseId == null) return null;
            // 检查时长，避免拿到试听版
            Object durObj = data.get(0).get("duration");
            if (durObj instanceof Number && ((Number) durObj).intValue() > 0 && ((Number) durObj).intValue() <= 30000) {
                log.info("网易云降级: {} 也只有试听版，跳过", keyword);
                return null;
            }
            // 尝试各音质
            String[] levels = {"exhigh", "higher", "standard"};
            for (String level : levels) {
                Map<String, Object> urlResult = neteaseApiService.getSongUrl(neteaseId, level);
                if (urlResult == null) continue;
                List<Map<String, Object>> urlData = (List<Map<String, Object>>) urlResult.get("data");
                if (urlData == null || urlData.isEmpty()) continue;
                String url = (String) urlData.get(0).get("url");
                if (url == null || url.isEmpty()) continue;
                Object trial = urlData.get(0).get("freeTrialInfo");
                Object time = urlData.get(0).get("time");
                if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) continue;
                log.info("网易云降级成功: {} → neteaseId={}, level={}", keyword, neteaseId, level);
                return url;
            }
            log.info("网易云降级: {} 所有音质均为试听，跳过", keyword);
        } catch (Exception e) {
            log.warn("网易云降级搜索失败: {} - {}", songName, e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
        return getPlayUrl(sourceId, null, null);
    }

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId, String songName, String artist) {
        // 1. 优先检查 RustFS 缓存
        String rustfsObjectName = "songs/" + sourceId + ".mp3";
        if (storageService.exists(rustfsObjectName)) {
            String directUrl = storageService.getDirectUrl(rustfsObjectName);
            log.info("getPlayUrl: {} 命中RustFS缓存", sourceId);
            return directUrl;
        }

        // 2. 尝试从 API 获取
        try {
            if (sourceId.matches("\\d+")) {
                String[] levels = {"exhigh", "higher", "standard"};
                for (String level : levels) {
                    Map<String, Object> result = neteaseApiService.getSongUrl(sourceId, level);
                    if (result == null) continue;
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data == null || data.isEmpty()) continue;
                    String url = (String) data.get(0).get("url");
                    if (url == null || url.isEmpty()) continue;
                    Object trial = data.get(0).get("freeTrialInfo");
                    Object time = data.get(0).get("time");
                    if (trial != null || (time instanceof Number && ((Number) time).intValue() <= 30000)) {
                        log.info("歌曲 {} 音质 {} 为试听, 尝试降级", sourceId, level);
                        continue;
                    }
                    return url;
                }
                // 所有音质都是试听 → 尝试 QQ 音乐降级
                log.info("getPlayUrl: 歌曲 {} 所有网易云音质均为试听，尝试QQ降级", sourceId);
                String qqUrl = tryQQFallback(songName, artist, sourceId);
                if (qqUrl != null) return qqUrl;

                log.warn("歌曲 {} 所有音质都是试听（含QQ降级）", sourceId);
                Map<String, Object> f = neteaseApiService.getSongUrl(sourceId, "standard");
                if (f != null) {
                    List<Map<String, Object>> d = (List<Map<String, Object>>) f.get("data");
                    if (d != null && !d.isEmpty()) return (String) d.get(0).get("url");
                }
            } else {
                Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) {
                        String qqUrl = (String) data.get(0).get("url");
                        if (qqUrl != null && !qqUrl.isEmpty()) return qqUrl;
                    }
                }
                // QQ 无URL → 尝试网易云降级
                log.info("getPlayUrl: QQ歌曲 {} 无播放链接，尝试网易云降级", sourceId);
                String neteaseUrl = tryNeteaseFallback(songName, artist, sourceId);
                if (neteaseUrl != null) return neteaseUrl;
            }
        } catch (Exception e) {
            log.warn("API获取播放链接失败, sourceId={}, 尝试DB兜底: {}", sourceId, e.getMessage());
        }

        // 3. API 失败 → 从 DB 兜底
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
        return song != null ? song.getUrl() : null;
    }

    public List<SongDTO> getRandomSongs(int count) {
        // 用热门歌曲关键词搜索，过滤掉无 sourceId 的非歌曲结果
        List<SongDTO> songs = search("热歌", 1, 30, null);
        songs = songs.stream()
                .filter(s -> s.getSourceId() != null && !s.getSourceId().isEmpty())
                .filter(s -> s.getDuration() == null || s.getDuration() > 30)
                .collect(Collectors.toList());
        Collections.shuffle(songs);
        if (songs.size() > count) return songs.subList(0, count);
        // 不够的话从 DB 已下载歌曲中补充
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

    public Song saveDownloadedSong(String sourceId, String name, String artist,
                                    String album, String coverUrl, Integer duration, String rustfsUrl) {
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
        if (song == null) {
            song = Song.builder().sourceId(sourceId).name(name).artist(artist)
                    .album(album).coverUrl(coverUrl).duration(duration).url(rustfsUrl).build();
            songMapper.insert(song);
        } else {
            song.setUrl(rustfsUrl);
            songMapper.updateById(song);
        }
        return song;
    }

    public Song getById(Long id) { return songMapper.selectById(id); }

    public Song getBySourceId(String sourceId) {
        return songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
    }
}
