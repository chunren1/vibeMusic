package com.vibemusic.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台判定单一事实源测试：钉死播放/歌词/搜索共用的 ID 形态识别口径，
 * 防止再次出现"播放按酷狗、歌词按 QQ"这类跨链路漂移。
 */
@DisplayName("歌曲 ID 形态判定测试")
class SongIdUtilsTest {

    private static final String KUGOU_HASH = "aabbccddeeff00112233445566778899";

    @Test
    @DisplayName("酷狗：32 位 hex（大小写不敏感）")
    void kugouHash() {
        assertTrue(SongIdUtils.isKugouHash(KUGOU_HASH));
        assertTrue(SongIdUtils.isKugouHash(KUGOU_HASH.toUpperCase()));
        assertFalse(SongIdUtils.isKugouHash("aabbcc"));                        // 长度不足
        assertFalse(SongIdUtils.isKugouHash("zzbbccddeeff00112233445566778899")); // 非 hex 字符
        assertFalse(SongIdUtils.isKugouHash(null));
    }

    @Test
    @DisplayName("B站：BV 开头（含 bvid|cid 复合形），裸数字不算")
    void biliId() {
        assertTrue(SongIdUtils.isBiliId("BV1xx411c7mD"));
        assertTrue(SongIdUtils.isBiliId("BV1xx411c7mD|123456"));
        assertFalse(SongIdUtils.isBiliId("12345"));
        assertFalse(SongIdUtils.isBiliId("av12345"));
        assertFalse(SongIdUtils.isBiliId(null));
    }

    @Test
    @DisplayName("网易云：纯数字")
    void neteaseId() {
        assertTrue(SongIdUtils.isNeteaseId("1895330088"));
        assertFalse(SongIdUtils.isNeteaseId("000rh0dE2TyUic"));
        assertFalse(SongIdUtils.isNeteaseId(null));
    }

    @Test
    @DisplayName("平台猜测优先级：bilibili > kugou > netease > qq（含无法识别的兜底）")
    void guessPlatform() {
        assertEquals("bilibili", SongIdUtils.guessPlatform("BV1xx411c7mD"));
        assertEquals("kugou", SongIdUtils.guessPlatform(KUGOU_HASH));
        assertEquals("netease", SongIdUtils.guessPlatform("1895330088"));
        assertEquals("qq", SongIdUtils.guessPlatform("000rh0dE2TyUic"));
        assertEquals("qq", SongIdUtils.guessPlatform(null));
        assertEquals("qq", SongIdUtils.guessPlatform(""));
    }
}
