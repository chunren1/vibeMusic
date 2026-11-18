package com.vibemusic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热歌本地缓存：容量、淘汰、路径安全。
 *
 * <p>这一层直接决定线上磁盘会不会被写满，以及播放端会不会读到半截文件，
 * 所以三条边界都要钉住：单曲上限、总容量上限、文件名不能带路径。</p>
 */
@DisplayName("热歌本地缓存")
class HotCacheServiceTest {

    @TempDir
    Path tmp;

    private HotCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new HotCacheService();
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "dir", tmp.toString());
        ReflectionTestUtils.setField(cache, "maxBytes", 1000L);
    }

    private boolean store(String id, int bytes) {
        return cache.store(id, new ByteArrayInputStream(new byte[bytes]), bytes);
    }

    @Test
    @DisplayName("写入后能命中，字节数如实统计")
    void storeThenLookup() {
        assertTrue(store("273114", 800));
        Path hit = cache.lookup("273114");
        assertNotNull(hit);
        assertTrue(Files.isRegularFile(hit));
        assertEquals(800L, cache.usedBytes());
    }

    @Test
    @DisplayName("文件名过滤路径穿越字符")
    void fileNameIsSafe() {
        assertEquals("___etc_passwd.mp3", HotCacheService.fileName("../etc/passwd"));
        assertEquals("BV1De411p77r.mp3", HotCacheService.fileName("BV1De411p77r"));
        assertEquals(".mp3", HotCacheService.fileName(""));
    }

    @Test
    @DisplayName("超预算时按最后访问时间淘汰最旧的")
    void evictsLeastRecentlyUsed() throws Exception {
        long base = 1_700_000_000_000L;
        assertTrue(store("old", 400));
        Files.setLastModifiedTime(cache.root().resolve("old.mp3"), FileTime.fromMillis(base));
        assertTrue(store("mid", 400));
        Files.setLastModifiedTime(cache.root().resolve("mid.mp3"), FileTime.fromMillis(base + 1000));
        // 第三次写入 400 → 总 1200 超预算 1000，必须淘汰最旧的 old
        assertTrue(store("new", 400));
        assertNull(cache.lookup("old"), "最久未访问的应被淘汰");
        assertNotNull(cache.lookup("mid"));
        assertNotNull(cache.lookup("new"));
        assertTrue(cache.usedBytes() <= 1000L);
    }

    @Test
    @DisplayName("lookup 会刷新访问时间，使常听的歌不被淘汰")
    void lookupRefreshesRecency() throws Exception {
        long base = 1_700_000_000_000L;
        assertTrue(store("a", 400));
        Files.setLastModifiedTime(cache.root().resolve("a.mp3"), FileTime.fromMillis(base));
        assertTrue(store("b", 400));
        Files.setLastModifiedTime(cache.root().resolve("b.mp3"), FileTime.fromMillis(base + 1000));

        // 访问 a，把它的时间推到最新
        assertNotNull(cache.lookup("a"));

        assertTrue(store("c", 400));
        assertNotNull(cache.lookup("a"), "刚访问过的 a 不该被淘汰");
        assertNull(cache.lookup("b"), "b 变成最旧的，应被淘汰");
    }

    @Test
    @DisplayName("声明长度超过单曲上限直接拒绝，不落盘")
    void rejectsOversizeSingle() {
        long tooBig = HotCacheService.MAX_SINGLE_BYTES + 1;
        assertFalse(cache.store("big", new ByteArrayInputStream(new byte[0]), tooBig));
        assertEquals(0L, cache.usedBytes());
    }

    @Test
    @DisplayName("关掉开关时既不写也不命中")
    void disabledIsInert() {
        ReflectionTestUtils.setField(cache, "enabled", false);
        assertFalse(store("x", 100));
        assertNull(cache.lookup("x"));
    }
}
