package com.vibemusic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 歌曲下载服务
 * <p>
 * 流程：Netease API → 下载 mp3 → 上传 RustFS → 存入 DB(song表)
 * <p>
 * 并发控制：使用 per-sourceId 的 ReentrantLock 防止同一首歌被并发重复下载，
 * 避免多次请求外部 API 和重复上传 RustFS 造成的资源浪费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    private final NeteaseApiService neteaseApiService;
    private final StorageService storageService;
    private final SongService songService;
    private final SongPlayService songPlayService;

    /** 每首歌一个锁，key 为 sourceId */
    private final ConcurrentHashMap<String, ReentrantLock> downloadLocks = new ConcurrentHashMap<>();

    /**
     * 下载歌曲到 RustFS 并入库（线程安全）
     */
    public String download(String sourceId, String name, String artist,
                           String album, String coverUrl, Integer duration,
                           String level) {
        ReentrantLock lock = downloadLocks.computeIfAbsent(sourceId, k -> new ReentrantLock());
        lock.lock();
        try {
            return doDownload(sourceId, name, artist, album, coverUrl, duration, level);
        } finally {
            lock.unlock();
            // 清理无等待者的锁，防止内存泄漏
            if (!lock.hasQueuedThreads()) {
                downloadLocks.remove(sourceId, lock);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    private String doDownload(String sourceId, String name, String artist,
                              String album, String coverUrl, Integer duration,
                              String level) {
        // 1. 获取锁后再次检查缓存（双重检查，避免锁外已检查但结果过时）
        String objectName = "songs/" + sourceId + ".mp3";
        if (storageService.exists(objectName)) {
            log.info("歌曲 {} 已缓存，直接入库", name);
            String url = storageService.getDirectUrl(objectName);
            songService.saveDownloadedSong(sourceId, name, artist, album, coverUrl, duration, url);
            return url;
        }

        // 2. 获取播放链接（自动识别QQ/网易云平台）
        log.info("获取播放链接: {} ({})", name, sourceId);
        String downloadUrl = songPlayService.getPlayUrl(sourceId);
        if (downloadUrl == null) {
            throw new RuntimeException("无法获取 VIP 播放链接");
        }

        // 3. 流式下载并上传到 RustFS（避免全量加载到内存）
        log.info("流式下载中: {}", name);
        java.io.File tempFile = null;
        try {
            tempFile = neteaseApiService.downloadSongToFile(downloadUrl);
            String rustfsUrl;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                rustfsUrl = storageService.uploadStream(objectName, fis, tempFile.length(), "audio/mpeg");
            }

            // 4. 存入 DB
            songService.saveDownloadedSong(sourceId, name, artist, album, coverUrl, duration, rustfsUrl);

            log.info("下载完成: {} -> RustFS", name);
            return rustfsUrl;
        } catch (java.io.IOException e) {
            throw new RuntimeException("流式下载上传失败: " + name, e);
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }
}
