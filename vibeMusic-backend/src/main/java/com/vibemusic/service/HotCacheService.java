package com.vibemusic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 热门歌曲本地缓存（320k，落在宿主机 bind mount 上）。
 *
 * <p>为什么要有这一层：取链是一次「三方赌博」——网易云会 -460、QQ 的 vkey 会过期、
 * 酷狗/咪咕的直链带时效签名。命中的歌一旦落到本地，播放链路就与上游彻底解耦：
 * 首字节来自磁盘，断网/风控/换 Cookie 都不影响。</p>
 *
 * <p>容量口径（2026-11-17 与用户确认）：单曲按 320k 存，约 9.6MB/4min；
 * 预算 {@code hot-cache.max-bytes} 默认 6GB（≈640 首），磁盘水位控制在 65% 以内。
 * 淘汰用「最后访问时间」做 LRU——{@link #lookup} 每次命中都会 touch，所以真正常听的
 * 歌不会被冷门歌挤掉。</p>
 *
 * <p>并发：写入走「临时文件 + 原子改名」，播放端要么看不见文件，要么看见完整文件，
 * 不会读到半截 MP3（Media3 对半截文件会抛 ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE）。</p>
 */
@Slf4j
@Service
public class HotCacheService {

    /** 单曲硬上限：40MB（320k 约 12 分钟），超过基本是上游返回了错误页或整轨 FLAC。 */
    static final long MAX_SINGLE_BYTES = 40L * 1024L * 1024L;

    /** 磁盘保留量：蓝绿发布瞬时需要约 1.5GB（镜像 + save 中转），低于此值不写缓存。 */
    static final long DISK_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L;

    @Value("${hot-cache.enabled:true}")
    private boolean enabled;

    @Value("${hot-cache.dir:/app/hot-cache}")
    private String dir;

    @Value("${hot-cache.max-bytes:6442450944}")
    private long maxBytes;

    public boolean isEnabled() {
        return enabled;
    }

    public Path root() {
        return Paths.get(dir);
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * 缓存文件名：只保留字母数字与下划线，避免 {@code ../} 之类的路径注入。
     * sourceId 可能是数字（网易云）、32 位 hash（酷狗）、BV 号（B站），统一按这个规则落盘。
     */
    static String fileName(String sourceId) {
        String safe = sourceId == null ? "" : sourceId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe + ".mp3";
    }

    /** 元信息旁挂文件：由预热任务写入，流接口读它回放 X-Play-* 响应头。 */
    public Path metaPath(String sourceId) {
        return root().resolve(fileName(sourceId).replace(".mp3", ".json"));
    }

    /** 命中返回文件路径（同时刷新最后访问时间），未命中返回 null。 */
    public Path lookup(String sourceId) {
        if (!enabled || sourceId == null || sourceId.isBlank()) return null;
        Path p = root().resolve(fileName(sourceId));
        try {
            if (Files.isRegularFile(p) && Files.size(p) > 0) {
                touch(p);
                return p;
            }
        } catch (Exception e) {
            log.debug("热歌缓存查询失败 {}: {}", sourceId, e.getMessage());
        }
        return null;
    }

    void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception ignored) {
            // touch 只是 LRU 的排序依据，失败不影响播放
        }
    }

    /** 目录内 .mp3 总字节数。 */
    public long usedBytes() {
        Path root = root();
        if (!Files.isDirectory(root)) return 0L;
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".mp3"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 可用磁盘空间（缓存目录所在分区）。 */
    long usableDiskBytes() {
        try {
            Files.createDirectories(root());
            return Files.getFileStore(root()).getUsableSpace();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 预算内是否还放得下 {@code bytes} 字节：容量上限与磁盘保留量取更严的那个。 */
    boolean hasRoomFor(long bytes) {
        long byQuota = maxBytes - usedBytes();
        long byDisk = usableDiskBytes() - DISK_RESERVE_BYTES;
        return bytes <= Math.min(byQuota, byDisk);
    }

    /**
     * 落盘：临时文件写完再原子改名，最后按需淘汰。
     *
     * @param in            音频字节流（由调用方负责关闭）
     * @param contentLength 上游声明长度；<=0 表示未知
     * @return true 表示文件已就位
     */
    public synchronized boolean store(String sourceId, InputStream in, long contentLength) {
        if (!enabled || sourceId == null || sourceId.isBlank() || in == null) return false;
        if (contentLength > MAX_SINGLE_BYTES) {
            log.info("热歌缓存跳过 {}: 声明长度 {} 超过单曲上限", sourceId, contentLength);
            return false;
        }
        long needed = Math.max(contentLength, 0L);
        // 磁盘余量不足是硬约束（留不出发布空间就绝不写）；容量上限则用淘汰来腾位，
        // 否则一旦装满，最常听的歌反而永远进不来。
        if (usableDiskBytes() - DISK_RESERVE_BYTES < needed) {
            log.info("热歌缓存跳过 {}: 磁盘余量不足（需要 {}B，保留 {}B）", sourceId, needed, DISK_RESERVE_BYTES);
            return false;
        }
        if (maxBytes - usedBytes() < needed) evictUntilRoomFor(needed);
        Path tmp = null;
        try {
            Files.createDirectories(root());
            tmp = Files.createTempFile(root(), "dl-", ".part");
            long total = 0L;
            byte[] buf = new byte[64 * 1024];
            try (OutputStream out = Files.newOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_SINGLE_BYTES) throw new IOException("单曲超过上限");
                    out.write(buf, 0, n);
                }
            }
            if (total <= 0L) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, root().resolve(fileName(sourceId)),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            evictToFit();
            log.info("热歌缓存写入 {} ({} MB)，目录现用 {} MB / 上限 {} MB",
                    sourceId, total / 1024 / 1024, usedBytes() / 1024 / 1024, maxBytes / 1024 / 1024);
            return true;
        } catch (Exception e) {
            log.warn("热歌缓存写入失败 {}: {}", sourceId, e.getMessage());
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
    }

    /** 超预算时按最后访问时间从旧到新淘汰，直到回到上限内。 */
    synchronized void evictToFit() {
        evictUntilRoomFor(0L);
    }

    /** 淘汰到「还能再放下 {@code needed} 字节」为止；返回后调用方仍需自行确认。 */
    synchronized void evictUntilRoomFor(long needed) {
        Path root = root();
        List<Path> files;
        try (Stream<Path> s = Files.list(root)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".mp3")).toList();
        } catch (Exception e) {
            return;
        }
        List<Path> ordered = files.stream().sorted(Comparator.comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        })).toList();
        long used = usedBytes();
        for (Path p : ordered) {
            if (maxBytes - used >= needed) break;
            try {
                long size = Files.size(p);
                String name = p.getFileName().toString();
                if (Files.deleteIfExists(p)) {
                    used -= size;
                    Files.deleteIfExists(root.resolve(name.replace(".mp3", ".json")));
                    log.info("热歌缓存淘汰 {} ({} MB)", name, size / 1024 / 1024);
                }
            } catch (Exception ignored) {
                // 单个文件删不掉就跳过，继续下一个
            }
        }
    }
}
