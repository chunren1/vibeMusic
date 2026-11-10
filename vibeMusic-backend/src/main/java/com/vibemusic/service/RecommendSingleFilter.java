package com.vibemusic.service;

import com.vibemusic.common.utils.SongIdUtils;
import com.vibemusic.dto.SongDTO;

/**
 * 首页推荐的质量门槛：首屏只放「能当单曲听」的歌。
 *
 * <p>背景（2026-11-10）：首页「随便听听」和游客推荐都走
 * {@link SongSearchService#getRandomSongs}，它从「热歌/新歌/流行…」里随机挑一个词做
 * <b>五平台聚合搜索</b>，B 站的视频/歌单音频会混进结果；用户画像扩展
 * （{@link RecommendPersonalizer}）按歌手搜同样会带回 B 站搬运。</p>
 *
 * <p>所以过滤放在推荐路径上，不动 {@code search()} 本身——用户主动搜「周杰伦合集」
 * 时本来就该搜得到，只有「首页替我挑」才需要门槛。</p>
 */
public final class RecommendSingleFilter {

    /** 短于 1 分钟多半是片段/试听；超过 7 分钟在流行曲里基本是连播/整张专辑。 */
    private static final int MIN_SECONDS = 60;
    private static final int MAX_SECONDS = 7 * 60;

    /**
     * 歌名**恰好等于**搜索热词的结果，是各平台把「流行」「热歌」当榜单条目返回的，
     * 不是单曲。用 equals 而不是 contains：「中华民谣」是正常歌曲，不能被误杀。
     */
    private static final java.util.Set<String> GENERIC_TITLES = java.util.Set.of(
            "热歌", "新歌", "流行", "华语", "民谣", "摇滚", "电子", "轻音乐", "经典", "影视原声",
            "抖音热歌", "网络热歌", "热门歌曲", "热门", "推荐"
    );

    /** 聚合/占位类歌手名：这些账号下的条目多为搬运或合辑切片。 */
    private static final java.util.Set<String> AGGREGATE_ARTISTS = java.util.Set.of(
            "华语群星", "群星", "未知", "未知歌手", "佚名", "网络歌手", "various artists", "unknown"
    );

    /**
     * 标题里出现这些词的一律不当单曲推——合集类内容即使平台不是 B 站也不该占首屏。
     * 只匹配小写化后的子串，英文词条已预置小写。
     */
    private static final String[] PLAYLIST_MARKS = {
            "合集", "合辑", "精选集", "串烧", "歌单", "连播", "完整专辑", "全专辑",
            // 「新歌」写进标题的多半是酷狗/咪咕的用户上传（实测"王奇生新歌谣"
            // "大运河新歌-…"），正规单曲不会这么命名
            "新歌",
            "playlist", "full album", "compilation"
    };

    private RecommendSingleFilter() {
    }

    /** @return true 表示这首歌适合出现在首页推荐位。 */
    public static boolean isSingle(SongDTO song) {
        if (song == null) return false;
        String id = song.getSourceId();
        if (id == null || id.isBlank()) return false;
        if (isBlank(song.getName()) || isBlank(song.getArtist())) return false;
        if ("bilibili".equals(SongIdUtils.guessPlatform(id))) return false;
        Integer duration = song.getDuration();
        // duration 缺失时放行：部分平台不返回时长，卡死会把正常单曲一起挡掉
        if (duration != null && (duration < MIN_SECONDS || duration > MAX_SECONDS)) return false;
        String name = song.getName().trim();
        String lower = name.toLowerCase();
        // 「流行」「热歌」这类榜单条目：歌名与搜索热词完全一致
        if (GENERIC_TITLES.contains(name)) return false;
        // 聚合账号（华语群星等）下的条目多为搬运
        if (AGGREGATE_ARTISTS.contains(song.getArtist().trim().toLowerCase())) return false;
        for (String mark : PLAYLIST_MARKS) {
            if (lower.contains(mark)) return false;
        }
        // 搬运标题常写成「某某《歌名》」并把歌手名塞进歌名，超过一定长度就不再像单曲名
        if (lower.contains("《") && name.length() > 16) return false;
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
