package com.vibemusic.common.utils;

/**
 * 歌曲 ID 形态判定（单一事实源）。
 *
 * <p>平台判定曾散落在播放（SongPlayService）、歌词（SongController）等多处且口径不一致
 * ——例如酷狗 32 位 hex 在播放链路按酷狗处理、在歌词链路因含字母被误判为 QQ。
 * 统一收敛到本类，新增平台/接入点时只改这里。
 */
public final class SongIdUtils {

    private SongIdUtils() {}

    /** 酷狗 track 判定：32 位 hex（专辑 hash 形，大小写不敏感）。 */
    public static boolean isKugouHash(String sourceId) {
        return sourceId != null && sourceId.matches("(?i)[a-f0-9]{32}");
    }

    /**
     * B站 track 判定：纯 bvid(BV 开头)或复合 bvid|cid。
     * BV 形与 QQ songmid 字符集有交集，故调用方必须优先显式 platform，
     * ID 猜测仅在无显式平台时生效；裸数字 aid 一律不视为 B 站(归网易云)。
     */
    public static boolean isBiliId(String sourceId) {
        if (sourceId == null) return false;
        String first = sourceId.split("\\|", -1)[0];
        return first.matches("BV[a-zA-Z0-9]+");
    }

    /** 网易云 track 判定：纯数字 id。 */
    public static boolean isNeteaseId(String sourceId) {
        return sourceId != null && sourceId.matches("\\d+");
    }

    /**
     * 从 ID 形态猜测平台（仅在调用方没有显式 platform 时使用）。
     *
     * @return {@code kugou} / {@code bilibili} / {@code netease} / {@code qq}；
     *         无法识别（含 null/空串）时按 {@code qq} 处理，与历史行为一致。
     */
    public static String guessPlatform(String sourceId) {
        if (isBiliId(sourceId)) return "bilibili";
        if (isKugouHash(sourceId)) return "kugou";
        if (isNeteaseId(sourceId)) return "netease";
        return "qq";
    }
}
