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
    @Transactional(rollbackFor = Exception.class)
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

        // 2. 获取播放链接（自动识别QQ/网易云平台）
        log.info("获取播放链接: {} ({})", name, sourceId);
        String downloadUrl = songService.getPlayUrl(sourceId);
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
