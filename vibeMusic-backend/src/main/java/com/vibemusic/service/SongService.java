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
 * 歌曲服务
 * <p>
 * 搜索流程: Redis 缓存(1h) → 未命中 → 网易云 API → 缓存 Redis → 返回 DTO
 * <p>
 * DB song 表仅存储已下载到 RustFS 的歌曲
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongService {

    private final SongRepository songRepository;
    private final NeteaseApiService neteaseApiService;
    private final SongCacheService cacheService;

    // ==================== 搜索 ====================

    /**
     * 搜索歌曲（Redis → 网易云 API）
     */
    @SuppressWarnings("unchecked")
    public List<SongDTO> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }
        keyword = keyword.trim();

        // 1. 查 Redis 缓存
        List<SongDTO> cached = cacheService.getSearchCache(keyword);
        if (!cached.isEmpty()) {
            log.info("搜索 '{}' Redis 命中 {} 首", keyword, cached.size());
            return cached;
        }

        // 2. 调用网易云 API
        log.info("搜索 '{}' Redis 未命中，调用网易云 API", keyword);
        try {
            Map<String, Object> result = neteaseApiService.search(keyword, 20);
            if (result == null) return Collections.emptyList();

            Map<String, Object> resultMap = (Map<String, Object>) result.get("result");
            if (resultMap == null) return Collections.emptyList();

            List<Map<String, Object>> songs = (List<Map<String, Object>>) resultMap.get("songs");
            if (songs == null || songs.isEmpty()) return Collections.emptyList();

            // 3. 解析为 SongDTO
            List<SongDTO> dtoList = new ArrayList<>();
            for (Map<String, Object> s : songs) {
                SongDTO dto = parseToDTO(s);
                if (dto != null) dtoList.add(dto);
            }

            // 4. 缓存到 Redis
            cacheService.setSearchCache(keyword, dtoList);
            log.info("搜索 '{}' 返回 {} 首，已缓存", keyword, dtoList.size());
            return dtoList;

        } catch (Exception e) {
            log.error("网易云搜索失败: {} (type: {})", e.getMessage(), e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    /**
     * 从网易云 API 原始数据解析为 SongDTO
     */
    private SongDTO parseToDTO(Map<String, Object> raw) {
        try {
            String sourceId = String.valueOf(raw.get("id"));

            // 歌手
            String artist = "未知歌手";
            Object arObj = raw.get("ar");
            if (arObj instanceof List<?> arList && !arList.isEmpty()) {
                Object first = arList.get(0);
                if (first instanceof Map<?, ?> m) {
                    Object val = m.get("name");
                    artist = val != null ? String.valueOf(val) : "未知歌手";
                }
            }

            // 专辑/封面
            String album = "";
            String coverUrl = "";
            Object alObj = raw.get("al");
            if (alObj instanceof Map<?, ?> alMap) {
                album = alMap.get("name") != null ? String.valueOf(alMap.get("name")) : "";
                coverUrl = alMap.get("picUrl") != null ? String.valueOf(alMap.get("picUrl")) : "";
            }

            // 时长（毫秒→秒）
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
            log.warn("解析歌曲数据失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 播放 ====================

    /**
     * 获取播放 URL（从网易云实时获取，不缓存 DB）
     */
    @SuppressWarnings("unchecked")
    public String getPlayUrl(String sourceId) {
        try {
            Map<String, Object> result = neteaseApiService.getSongUrl(sourceId, "exhigh");
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
            if (data != null && !data.isEmpty()) {
                return (String) data.get(0).get("url");
            }
        } catch (Exception e) {
            log.error("获取播放链接失败: {}", e.getMessage());
        }

        // 降级：查看 DB 中是否有已下载的 RustFS 地址
        Song song = songRepository.findBySourceId(sourceId);
        return song != null ? song.getUrl() : null;
    }

    // ==================== 随机推荐 ====================

    /**
     * 获取随机推荐（用热门关键词搜索 + 缓存）
     */
    public List<SongDTO> getRandomSongs(int count) {
        // 先用一个热门关键词填充缓存
        List<SongDTO> songs = search("热门推荐");

        if (songs.size() > count) {
            // 随机选
            Collections.shuffle(songs);
            return songs.subList(0, count);
        }

        if (songs.size() < count) {
            // 再从 DB 的已下载歌曲中补充
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

    // ==================== 下载后保存到 DB ====================

    /**
     * 下载成功后，将歌曲信息存储到 DB（用于长期缓存）
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
     * 根据 DB ID 查询（仅下载过的歌曲有 DB 记录）
     */
    public Song getById(Long id) {
        return songRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("歌曲不存在"));
    }
}
