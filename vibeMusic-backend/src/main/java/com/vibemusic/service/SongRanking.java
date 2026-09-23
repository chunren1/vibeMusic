package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 搜索结果的排序 / 相关性 / 门控纯逻辑（从 SongSearchService 拆出；round6 T1）。
 *
 * <p>全部为静态方法：输入合并后的 {@link SongDTO} 列表与关键词，输出打分/原地调整，
 * 不依赖任何 Spring 容器状态。拆出的目的只是让 SongSearchService 回到可读规模，
 * 语义与原实现逐行一致（原样搬运，不做"顺手修复"）。
 */
@Slf4j
final class SongRanking {

    private SongRanking() {
    }

    // 查询相关性加分叠加在平台分之上，不改变平台权重与跨平台加分。
    private static final double RELEVANCE_EXACT_NAME_BONUS = 2.0;
    private static final double RELEVANCE_NAME_CONTAINS_BONUS = 1.0;
    private static final double RELEVANCE_ARTIST_BONUS = 1.0;
    private static final double RELEVANCE_NAME_AND_ARTIST_BONUS = 1.0;

    /** 非 VIP 加权：用户侧无 VIP，适度降权付费内容（见 applyNonVipBonus 注释）。 */
    static final double NON_VIP_BONUS = 0.5;

    /**
     * 标题长度罚分：超过阈值的标题按超出字数线性扣分并封顶。
     * 阈值 30 字：正常单曲中文名多为 1–10 字，即使带 "(Live版)" / "feat. X" 等
     * 后缀也极少超 30；而拼盘/串烧/合集类标题常靠堆砌歌名冲到 50 字以上。
     * 取 B 站强行判定(>60 字判弱)的一半，保守起见只打超长、不误伤正常歌名。
     * 步长 0.05/字：超出 20 字扣 1.0，恰好抵消一档歌名包含加成(+1.0)，渐进而非一刀切。
     * 封顶 2.0 = 精确歌名加成(+2.0)：标题堆砌至多 cancel 掉精确匹配红利，
     * 不超过最大相关性信号；扣分后下限钳制 0，保证排序分非负、返回结构不变。
     * 按码点计数：emoji 堆砌标题按一个可见字符计，不因 UTF-16 代理对被双倍惩罚。
     */
    static final int TITLE_LENGTH_FREE = 30;
    static final double TITLE_LENGTH_PENALTY_PER_CHAR = 0.05;
    static final double TITLE_LENGTH_PENALTY_MAX = 2.0;

    /** B 站强行的播放量/弹幕门槛与弱行封顶分（与平台权重同源语义，见 applyBiliPlayGate）。 */
    static final long BILI_MIN_PLAYS = 100_000L;
    static final long BILI_MIN_DANMAKU = 1_000L;
    static final double BILI_WEAK_SCORE_CAP = 0.5;

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]");

    static double computeScore(int rank, double platformWeight) {
        return (1.0 / rank) * platformWeight;
    }

    static SongDTO pickBest(SongDTO a, SongDTO b) {
        boolean aTrial = a.getDuration() != null && a.getDuration() <= 30;
        boolean bTrial = b.getDuration() != null && b.getDuration() <= 30;
        if (aTrial && !bTrial) return b;
        if (!aTrial && bTrial) return a;
        double aQ = qualityScore(a);
        double bQ = qualityScore(b);
        if (aQ != bQ) return aQ > bQ ? a : b;
        double aS = a.getFinalScore() != null ? a.getFinalScore() : 0;
        double bS = b.getFinalScore() != null ? b.getFinalScore() : 0;
        return aS >= bS ? a : b;
    }

    private static double qualityScore(SongDTO song) {
        double score = 0;
        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) score += 0.5;
        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) score += 0.5;
        return score;
    }

    static String normalizeKey(String name, String artist) {
        return normalizeText((name != null ? name : "") + "|" + (artist != null ? artist : ""));
    }

    static String normalizeText(String s) {
        if (s == null) return "";
        return NON_ALPHANUM.matcher(s.toLowerCase().replaceAll("\\s+", "")).replaceAll("");
    }

    static List<String> tokenizeQuery(String keyword) {
        if (keyword == null) return List.of();
        return Arrays.stream(keyword.trim().split("\\s+"))
                .map(SongRanking::normalizeText)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    static void applyRelevanceBonus(List<SongDTO> merged, String keyword) {
        if (merged.isEmpty()) return;
        List<String> tokens = tokenizeQuery(keyword);
        if (tokens.isEmpty()) return;
        String normQuery = normalizeText(keyword);
        for (SongDTO song : merged) {
            double bonus = computeRelevanceBonus(song, tokens, normQuery);
            if (bonus > 0) {
                double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
                song.setFinalScore(base + bonus);
            }
        }
    }

    private static double computeRelevanceBonus(SongDTO song, List<String> normTokens, String normQuery) {
        String normName = normalizeText(song.getName());
        if (normName.isEmpty()) return 0;
        String normArtist = normalizeText(song.getArtist());
        double bonus = 0;
        boolean nameMatch = false;
        if (!normQuery.isEmpty() && normName.equals(normQuery)) {
            bonus += RELEVANCE_EXACT_NAME_BONUS;
            nameMatch = true;
        } else {
            for (String t : normTokens) {
                if (normName.equals(t)) {
                    bonus += RELEVANCE_EXACT_NAME_BONUS;
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch) {
                for (String t : normTokens) {
                    if (normName.contains(t) || t.contains(normName)) {
                        bonus += RELEVANCE_NAME_CONTAINS_BONUS;
                        nameMatch = true;
                        break;
                    }
                }
            }
        }
        boolean artistMatch = false;
        if (!normArtist.isEmpty()) {
            for (String t : normTokens) {
                if (normArtist.contains(t) || t.contains(normArtist)) {
                    bonus += RELEVANCE_ARTIST_BONUS;
                    artistMatch = true;
                    break;
                }
            }
        }
        if (nameMatch && artistMatch) bonus += RELEVANCE_NAME_AND_ARTIST_BONUS;
        return bonus;
    }

    /**
     * B 站播放量门控：纯 B 站来源行必须同时满足歌曲形态(有封面 + 标题成形)与
     * 热度(播放量或弹幕任一达标)，否则封顶至 {@link #BILI_WEAK_SCORE_CAP} 沉底。
     * 跨平台合并行(availableSources 含其他平台)不封顶：该行有多源可用性背书，
     * 质量由 pickBest 的专辑/封面完整度决定，不再受单源热度惩罚。
     */
    static void applyBiliPlayGate(List<SongDTO> merged) {
        for (SongDTO song : merged) {
            if (!"bilibili".equals(song.getPlatform())) continue;
            List<String> sources = song.getAvailableSources();
            if (sources != null && !(sources.size() == 1 && sources.contains("bilibili"))) continue;
            if (isBiliStrong(song)) continue;
            double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
            if (base > BILI_WEAK_SCORE_CAP) {
                song.setFinalScore(BILI_WEAK_SCORE_CAP);
                log.debug("[BILI-GATE] 弱行沉底: name='{}', cover={}, plays={}, danmaku={}",
                        song.getName(), song.getCoverUrl() != null && !song.getCoverUrl().isBlank(),
                        song.getPlayCount(), song.getDanmakuCount());
            }
        }
    }

    /**
     * 强 B 站行判定：有封面(经 image-proxy 代取后恒非空，空=上游缺图) +
     * 标题成形(非空、≤60字、无残留视频标题括号【】，即网关解析成功或本就干净) +
     * (播放量达标 OR 弹幕达标；缺失(null，老缓存/ES回填)按 0 计→弱行)。
     */
    static boolean isBiliStrong(SongDTO song) {
        if (song.getCoverUrl() == null || song.getCoverUrl().isBlank()) return false;
        String name = song.getName();
        if (name == null || name.isBlank() || name.length() > 60) return false;
        if (name.contains("【") || name.contains("】")) return false;
        long plays = song.getPlayCount() != null ? song.getPlayCount() : 0L;
        long danmaku = song.getDanmakuCount() != null ? song.getDanmakuCount() : 0L;
        return plays >= BILI_MIN_PLAYS || danmaku >= BILI_MIN_DANMAKU;
    }

    /**
     * 非 VIP 加权（merge 后统一追加，不碰 pickBest 试听版逻辑）。
     * vip == false（上游明确非付费）→ +0.5；vip == true → +0；
     * vip == null（上游未知，ES/DB 回填结果亦无此字段）→ +0：未知不得排到已知可用之前。
     */
    static void applyNonVipBonus(List<SongDTO> merged) {
        for (SongDTO song : merged) {
            if (Boolean.FALSE.equals(song.getVip())) {
                double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
                song.setFinalScore(base + NON_VIP_BONUS);
            }
        }
    }

    static double titleLengthPenalty(String name) {
        if (name == null || name.isEmpty()) return 0;
        int len = name.codePointCount(0, name.length());
        if (len <= TITLE_LENGTH_FREE) return 0;
        return Math.min(TITLE_LENGTH_PENALTY_MAX,
                (len - TITLE_LENGTH_FREE) * TITLE_LENGTH_PENALTY_PER_CHAR);
    }

    static void applyTitleLengthPenalty(List<SongDTO> merged) {
        for (SongDTO song : merged) {
            double penalty = titleLengthPenalty(song.getName());
            if (penalty > 0) {
                double base = song.getFinalScore() != null ? song.getFinalScore() : 0;
                song.setFinalScore(Math.max(0, base - penalty));
            }
        }
    }
}
