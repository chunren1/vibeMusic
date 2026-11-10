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

    /** 短于 1 分钟多半是片段/试听，长于 15 分钟多半是整张专辑或歌单连播。 */
    private static final int MIN_SECONDS = 60;
    private static final int MAX_SECONDS = 15 * 60;

    /**
     * 标题里出现这些词的一律不当单曲推——合集类内容即使平台不是 B 站也不该占首屏。
     * 只匹配小写化后的子串，英文词条已预置小写。
     */
    private static final String[] PLAYLIST_MARKS = {
            "合集", "合辑", "精选集", "串烧", "歌单", "连播", "完整专辑", "全专辑",
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
        String name = song.getName().toLowerCase();
        for (String mark : PLAYLIST_MARKS) {
            if (name.contains(mark)) return false;
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
