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

    private static final double NET_WEIGHT = 1.0;
    private static final double QQ_WEIGHT = 0.9;
    private static final double CROSS_PLATFORM_BONUS = 0.3;
    private static final int PER_PLATFORM_FETCH = 20;
    private static final int SEARCH_TIMEOUT_SEC = 4;
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

    @SuppressWarnings("unchecked")
    public List<SongDTO> search(String keyword, int page, int size) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        final String kw = keyword.trim();

        List<SongDTO> cached = cacheService.getSearchCache(kw, page);
        if (cached != null && !cached.isEmpty()) {
            List<SongDTO> all = cacheService.getSearchCache(kw, 1);
            if (all != null && !all.isEmpty()) {
                int from = (page - 1) * size;
                int to = Math.min(from + size, all.size());
                if (from >= all.size()) return Collections.emptyList();
                return all.subList(from, to);
            }
        }

        log.info("Search '{}' page={} Redis miss", kw, page);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<List<SongDTO>> neF = executor.submit(() -> safeSearchNetease(kw));
        Future<List<SongDTO>> qqF = executor.submit(() -> safeSearchQQ(kw));
        List<SongDTO> neteaseSongs = getWithTimeout(neF, SEARCH_TIMEOUT_SEC, "Netease");
        List<SongDTO> qqSongs = getWithTimeout(qqF, SEARCH_TIMEOUT_SEC, "QQ");
        executor.shutdownNow();

        Map<String, SongDTO> mergedMap = new LinkedHashMap<>();

        for (int i = 0; i < neteaseSongs.size(); i++) {
            SongDTO song = neteaseSongs.get(i);
            double finalScore = (1.0 / (i + 1)) * NET_WEIGHT;
            song.setFinalScore(finalScore);
            song.setPlatform("netease");
            song.setAvailableSources(new ArrayList<>(List.of("netease")));
            String key = normalizeKey(song.getName(), song.getArtist());
            if (!mergedMap.containsKey(key) || mergedMap.get(key).getFinalScore() < finalScore) {
                mergedMap.put(key, song);
            }
        }

        for (int i = 0; i < qqSongs.size(); i++) {
            SongDTO song = qqSongs.get(i);
            double finalScore = (1.0 / (i + 1)) * QQ_WEIGHT;
            String key = normalizeKey(song.getName(), song.getArtist());
            SongDTO existing = mergedMap.get(key);
            if (existing != null) {
                if (existing.getAvailableSources() == null) existing.setAvailableSources(new ArrayList<>());
                if (!existing.getAvailableSources().contains("qq")) existing.getAvailableSources().add("qq");
                if ((existing.getCoverUrl() == null || existing.getCoverUrl().isEmpty()) && song.getCoverUrl() != null) {
                    existing.setCoverUrl(song.getCoverUrl());
                }
                existing.setFinalScore(existing.getFinalScore() + CROSS_PLATFORM_BONUS);
            } else {
                song.setFinalScore(finalScore);
                song.setPlatform("qq");
                song.setAvailableSources(new ArrayList<>(List.of("qq")));
                mergedMap.put(key, song);
            }
        }

        List<SongDTO> resultList = new ArrayList<>(mergedMap.values());
        resultList.sort((a, b) -> Double.compare(
                b.getFinalScore() != null ? b.getFinalScore() : 0,
                a.getFinalScore() != null ? a.getFinalScore() : 0));

        cacheService.setSearchCache(kw, page, resultList, !resultList.isEmpty());

        int from = (page - 1) * size;
        int to = Math.min(from + size, resultList.size());
        if (from >= resultList.size()) return Collections.emptyList();
        return resultList.subList(from, to);
    }

    public List<SongDTO> search(String keyword) {
        return search(keyword, 1, 20);
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

    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
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
                log.warn("歌曲 {} 所有音质都是试听", sourceId);
                Map<String, Object> f = neteaseApiService.getSongUrl(sourceId, "standard");
                if (f != null) {
                    List<Map<String, Object>> d = (List<Map<String, Object>>) f.get("data");
                    if (d != null && !d.isEmpty()) return (String) d.get(0).get("url");
                }
            } else {
                Map<String, Object> result = neteaseApiService.getQQSongUrl(sourceId);
                if (result != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
                    if (data != null && !data.isEmpty()) return (String) data.get(0).get("url");
                }
            }
        } catch (Exception e) { log.error("Failed to get playback URL: {}", e.getMessage()); }
        Song song = songMapper.selectOne(new LambdaQueryWrapper<Song>().eq(Song::getSourceId, sourceId));
        return song != null ? song.getUrl() : null;
    }

    public List<SongDTO> getRandomSongs(int count) {
        List<SongDTO> songs = search("热门推荐");
        if (songs.size() > count) { Collections.shuffle(songs); return songs.subList(0, count); }
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
        } else { song.setUrl(rustfsUrl); }
        songMapper.insert(song);
        return song;
    }

    public Song getById(Long id) { return songMapper.selectById(id); }
}
