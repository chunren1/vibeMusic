package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.Song;
import com.vibemusic.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Song Service
 * <p>
 * Search flow: Redis cache (1h) -> miss -> multi-platform aggregated search (QQ + Netease) -> cache -> return DTO
 * <p>
 * DB song table only stores songs downloaded to RustFS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;

    // ==================== Search ====================

    /**
     * Search songs (Redis -> multi-platform aggregated search)
     */
    @SuppressWarnings("unchecked")
    public List<SongDTO> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        keyword = keyword.trim();

        // 1. Check Redis cache
        List<SongDTO> cached = cacheService.getSearchCache(keyword);
        if (!cached.isEmpty()) {
            log.info("Search '{}' Redis hit {} results", keyword, cached.size());
            return cached;
        }

        // 2. Call multi-platform aggregated search (QQ + Netease)
        log.info("Search '{}' Redis miss, calling multi-platform aggregated search", keyword);
        try {
            Map<String, Object> result = neteaseApiService.aggregatedSearch(keyword, 1, 20);
            if (result == null) return Collections.emptyList();

            // Aggregated API returns {code: 200, data: {list: [...], total, page, size}}
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return Collections.emptyList();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            if (list == null || list.isEmpty()) return Collections.emptyList();

            // 3. Parse to SongDTO (multi-platform unified format)
            List<SongDTO> dtoList = new ArrayList<>();
            for (Map<String, Object> item : list) {
                SongDTO dto = parseAggregatedToDTO(item);
                if (dto != null) dtoList.add(dto);
            }

            // 4. Cache to Redis (only cache when there are results)
            if (!dtoList.isEmpty()) {
                cacheService.setSearchCache(keyword, dtoList);
                log.info("Search '{}' returned {} results (QQ+Netease aggregated), cached", keyword, dtoList.size());
            } else {
                log.info("Search '{}' returned 0 results, not cached", keyword);
            }
            return dtoList;

        } catch (Exception e) {
            log.error("Aggregated search failed: {} (type: {})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    /**
     * Parse raw Netease API data to SongDTO
     */
    private SongDTO parseToDTO(Map<String, Object> raw) {
        try {
            String sourceId = String.valueOf(raw.get("id"));

            // Artist
            String artist = "\u672a\u77e5\u6b4c\u624b";
            Object arObj = raw.get("ar");
            if (arObj instanceof List<?> arList && !arList.isEmpty()) {
                Object first = arList.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object val = m.get("name");
                    artist = val != null ? String.valueOf(val) : "\u672a\u77e5\u6b4c\u624b";
                }
            }

            // Album/Cover
            String album = "";
            String coverUrl = "";
            Object alObj = raw.get("al");
            if (alObj instanceof Map<?, ?> alMap) {
                album = alMap.get("name") != null ? String.valueOf(alMap.get("name")) : "";
                coverUrl = alMap.get("picUrl") != null ? String.valueOf(alMap.get("picUrl")) : "";
            }

            // Duration (ms -> s)
            int duration = 0;
            if (raw.get("dt") instanceof Number dtNum) {
                duration = dtNum.intValue() / 1000;
            }

            return SongDTO.builder()
                    .sourceId(sourceId)
                    .name(String.valueOf(raw.get("name")))
                    .artist(artist)
                    .album(album)
                    .coverUrl(coverUrl)
                    .duration(duration)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse song data: {}", e.getMessage());
            return null;
        }
    }


    /**
     * Parse aggregated search format to SongDTO (QQ + Netease unified format)
     */
    private SongDTO parseAggregatedToDTO(Map<String, Object> raw) {
        try {
            String sourceId = String.valueOf(raw.get("id"));
            String name = raw.get("name") != null ? String.valueOf(raw.get("name")) : "";
            String artists = raw.get("artists") != null ? String.valueOf(raw.get("artists")) : "\u672a\u77e5\u6b4c\u624b";
            String album = raw.get("album") != null ? String.valueOf(raw.get("album")) : "";
            String coverUrl = raw.get("cover") != null ? String.valueOf(raw.get("cover")) : "";
            String platform = raw.get("platform") != null ? String.valueOf(raw.get("platform")) : "netease";

            // duration: aggregated search returns milliseconds
            int duration = 0;
            Object durObj = raw.get("duration");
            if (durObj instanceof Number) duration = ((Number) durObj).intValue() / 1000;

            SongDTO dto = new SongDTO();
            dto.setSourceId(sourceId);
            dto.setName(name);
            dto.setArtist(artists);
            dto.setAlbum(album);
            dto.setCoverUrl(coverUrl);
            dto.setDuration(duration);
            dto.setPlatform(platform);
            return dto;
        } catch (Exception e) {
            log.warn("Failed to parse aggregated search song: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Playback ====================

    /**
     * Get playback URL (real-time from Netease, not cached in DB)
     */
    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
        try {
            // Platform detection: QQ ID contains letters, Netease ID is pure numbers
            Map<String, Object> result;
            if (sourceId.matches("\\d+")) {
                result = neteaseApiService.getSongUrl(sourceId, "exhigh");
            } else {
                result = neteaseApiService.getQQSongUrl(sourceId);
            }
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data != null && !data.isEmpty()) {
                return (String) data.get(0).get("url");
            }
        } catch (Exception e) {
            log.error("Failed to get playback URL: {}", e.getMessage());
        }

        // Fallback: check DB for downloaded RustFS URL
        Song song = songRepository.findBySourceId(sourceId);
        return song != null ? song.getUrl() : null;
    }

    // ==================== Random Recommendations ====================

    /**
     * Get random recommendations (search with popular keyword + cache)
     */
    public List<SongDTO> getRandomSongs(int count) {
        // Use a popular keyword to populate cache
        List<SongDTO> songs = search("\u70ed\u95e8\u63a8\u8350");

        if (songs.size() > count) {
            // Random shuffle
            Collections.shuffle(songs);
            return songs.subList(0, count);
        }

        if (songs.size() < count) {
            // Supplement from DB downloaded songs
            List<Song> dbSongs = songRepository.findRandomSongs(count - songs.size());
            List<SongDTO> dbDtos = dbSongs.stream().map(s -> SongDTO.builder()
                    .sourceId(s.getSourceId())
                    .name(s.getName())
                    .artist(s.getArtist())
                    .album(s.getAlbum())
                    .coverUrl(s.getCoverUrl())
                    .duration(s.getDuration())
                    .build()
            ).collect(Collectors.toList());
            songs.addAll(dbDtos);
        }

        return songs;
    }

    // ==================== Save Downloaded Song to DB ====================

    /**
     * Save song info to DB after successful download (for long-term cache)
     */
    public Song saveDownloadedSong(String sourceId, String name, String artist,
                                    String album, String coverUrl, Integer duration,
                                    String rustfsUrl) {
        Song song = songRepository.findBySourceId(sourceId);
        if (song == null) {
            song = Song.builder()
                    .sourceId(sourceId)
                    .name(name)
                    .artist(artist)
                    .album(album)
                    .coverUrl(coverUrl)
                    .duration(duration)
                    .url(rustfsUrl)
                    .build();
        } else {
            song.setUrl(rustfsUrl);
        }
        return songRepository.save(song);
    }

    /**
     * Query by DB ID (only downloaded songs have DB records)
     */
    public Song getById(Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("\u6b4c\u66f2\u4e0d\u5b58\u5728"));
    }
}