package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
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
     * <p>
     * 事务拆分：HTTP I/O（下载 mp3 + 上传 MinIO）在事务外完成，
     * 仅 DB 持久化在短事务内执行，避免长事务阻塞连接池。
     */
    public String download(String sourceId, String name, String artist,
                           String album, String coverUrl, Integer duration,
                           String level) {
        ReentrantLock lock = downloadLocks.computeIfAbsent(sourceId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 阶段 1：HTTP/文件 I/O（锁保护，无事务）
            String rustfsUrl = downloadAndUpload(sourceId, name, artist, album, coverUrl, duration, level);
            // 阶段 2：DB 持久化（短事务，< 50ms）
            persistToDb(sourceId, name, artist, album, coverUrl, duration, rustfsUrl);
            return rustfsUrl;
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                downloadLocks.remove(sourceId, lock);
            }
        }
    }

    /**
     * 阶段 1：下载 + 上传 RustFS（无事务，HTTP I/O 可能耗时 30s+）
     */
    private String downloadAndUpload(String sourceId, String name, String artist,
                                     String album, String coverUrl, Integer duration,
                                     String level) {
        String objectName = "songs/" + sourceId + ".mp3";

        // 双重检查：获取锁后再次检查 RustFS 缓存
        if (storageService.exists(objectName)) {
            log.info("歌曲 {} 已缓存，跳过下载", name);
            return storageService.getDirectUrl(objectName);
        }

        // 获取播放链接
        log.info("获取播放链接: {} ({})", name, sourceId);
        String downloadUrl = songPlayService.getPlayUrl(sourceId);
        if (downloadUrl == null) {
            throw new BusinessException(502, "无法获取 VIP 播放链接");
        }

        // 流式下载并上传到 RustFS
        log.info("流式下载中: {}", name);
        java.io.File tempFile = null;
        try {
            tempFile = neteaseApiService.downloadSongToFile(downloadUrl);
            String rustfsUrl;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                rustfsUrl = storageService.uploadStream(objectName, fis, tempFile.length(), "audio/mpeg");
            }
            log.info("下载上传完成: {} -> RustFS", name);
            return rustfsUrl;
        } catch (java.io.IOException e) {
            throw new BusinessException(500, "流式下载上传失败: " + name);
        } finally {
            if (tempFile != null && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    /**
     * 阶段 2：仅 DB 操作（短事务，无 HTTP I/O）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void persistToDb(String sourceId, String name, String artist,
                               String album, String coverUrl, Integer duration,
                               String rustfsUrl) {
        songService.saveDownloadedSong(sourceId, name, artist, album, coverUrl, duration, rustfsUrl);
    }
}
