package com.vibemusic.service;

import com.vibemusic.entity.Song;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 歌曲下载服务
 * <p>
 * 流程：Netease API → 下载 mp3 → 上传 RustFS → 存入 DB(song表)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final SongService songService;

    /**
     * 下载歌曲到 RustFS 并入库
     */
    @Transactional
    public String download(String sourceId, String name, String artist,
                           String album, String coverUrl, Integer duration,
                           String level) {
        // 1. 检查 RustFS 是否已有缓存
        String objectName = "songs/" + sourceId + ".mp3";
        if (storageService.exists(objectName)) {
            log.info("歌曲 {} 已缓存，直接入库", name);
            String url = storageService.getPresignedUrl(objectName);
            songService.saveDownloadedSong(sourceId, name, artist, album, coverUrl, duration, url);
            return url;
        }

        // 2. 从网易云获取播放链接
        log.info("获取播放链接: {} ({})", name, sourceId);
        Map<String, Object> result = neteaseApiService.getSongUrl(sourceId, level != null ? level : "exhigh");

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> dataList =
                (java.util.List<Map<String, Object>>) result.get("data");
        if (dataList == null || dataList.isEmpty()) {
            throw new RuntimeException("获取播放链接失败");
        }

        String downloadUrl = (String) dataList.get(0).get("url");
        if (downloadUrl == null) {
            throw new RuntimeException("无法获取 VIP 播放链接");
        }

        // 3. 下载 mp3
        log.info("下载中: {}", name);
        byte[] mp3Data = neteaseApiService.downloadSong(downloadUrl);

        // 4. 上传 RustFS
        String rustfsUrl = storageService.upload(objectName, mp3Data, "audio/mpeg");

        // 5. 存入 DB
        songService.saveDownloadedSong(sourceId, name, artist, album, coverUrl, duration, rustfsUrl);

        log.info("下载完成: {} -> RustFS", name);
        return rustfsUrl;
    }
}
