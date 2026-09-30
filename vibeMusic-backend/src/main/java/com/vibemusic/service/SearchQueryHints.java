package com.vibemusic.service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索查询提示纯逻辑：拼音/首字母别名、拼写纠错、空结果回退建议。
 *
 * <p>全部为静态方法，不依赖 Spring 容器（与 SongRanking 同风格）。
 * fanout/超时预算一律不动：本类只产出"建议关键词"，是否发起二次搜索由调用方
 * （App 一键点选）决定，绝不在主搜索路径内追加上游调用。
 *
 * <p>体积说明：拼音支持仅覆盖预热热词的精选别名表（约 20 条，零依赖）；
 * 全量汉字拼音库（体积 MB 级，需引入大拼音表依赖）因包体积成本暂缓，
 * 生僻查询走上游 iscorrection/模糊搜索兜底。
 */
final class SearchQueryHints {

    private SearchQueryHints() {
    }

    /**
     * 拼音全拼/首字母 → 标准关键词（键全小写无空格；仅覆盖 HOT_KEYWORDS 范围）。
     * 与 App 端 SEARCH_PINYIN_ALIASES 同源，增删热词时两边同步。
     */
    static final Map<String, String> PINYIN_ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("zhoujielun", "周杰伦");
        m.put("zjl", "周杰伦");
        m.put("chenyixun", "陈奕迅");
        m.put("cyx", "陈奕迅");
        m.put("linjunjie", "林俊杰");
        m.put("ljj", "林俊杰");
        m.put("dengziqi", "邓紫棋");
        m.put("dzq", "邓紫棋");
        m.put("qingtian", "晴天");
        m.put("qt", "晴天");
        m.put("daoxiang", "稻香");
        m.put("dx", "稻香");
        m.put("yequ", "夜曲");
        m.put("yq", "夜曲");
        m.put("qilixiang", "七里香");
        m.put("qlx", "七里香");
        m.put("gaobaiqiqiu", "告白气球");
        m.put("gbqq", "告白气球");
        m.put("rege", "热歌");
        m.put("rg", "热歌");
        return Map.copyOf(m);
    }

    /**
     * 查询归一化：NFKC（全角→半角、兼容字符折叠）+ 去首尾空白 + 压缩连续空白。
     * 刻意不做小写折叠：保持缓存键与上游查询与历史行为一致（大小写由上游自行不敏感处理）。
     */
    static String normalizeQuery(String query) {
        if (query == null) return "";
        String n = Normalizer.normalize(query, Normalizer.Form.NFKC);
        n = n.trim().replaceAll("\\s+", " ");
        return n;
    }

    /**
     * 别名解析：拼音/首字母 → 标准词；非别名返回 null。大小写与空格不敏感。
     */
    static String resolveAlias(String query) {
        if (query == null) return null;
        String key = normalizeQuery(query).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (key.isEmpty()) return null;
        return PINYIN_ALIASES.get(key);
    }

    /**
     * 拼写纠错：与热词编辑距离 ≤2（查询至少 2 字）时返回最接近的热词，否则 null。
     * 完全命中（距离 0）返回 null：无需纠错。
     */
    static String didYouMean(String query, List<String> hotwords) {
        if (query == null || hotwords == null || hotwords.isEmpty()) return null;
        String q = normalizeQuery(query);
        if (q.codePointCount(0, q.length()) < 2) return null;
        if (hotwords.contains(q)) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String hot : hotwords) {
            if (hot == null || hot.isEmpty() || hot.equals(q)) continue;
            int d = editDistance(q, hot);
            if (d < bestDist) {
                bestDist = d;
                best = hot;
            }
        }
        return bestDist <= 2 ? best : null;
    }

    /** 经典 Levenshtein（码点级；查询与热词皆短串，直接 DP 无需截断优化）。 */
    static int editDistance(String a, String b) {
        int[] s1 = a.codePoints().toArray();
        int[] s2 = b.codePoints().toArray();
        int n = s1.length;
        int m = s2.length;
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = s1[i - 1] == s2[j - 1] ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    /**
     * 空结果回退建议，优先级：别名 ＞ 纠错 ＞ 归一化差异（全半角/多空格修正）。
     * 全上游失败时返回 null：此时唯一有效动作是稍后重试，不给误导性改写建议。
     * 无任何建议时返回 null（调用方仅展示重试）。
     */
    static String suggestForEmpty(String rawKeyword, List<String> hotwords, boolean allUpstreamFailed) {
        if (allUpstreamFailed) return null;
        if (rawKeyword == null) return null;
        String trimmed = rawKeyword.trim();
        if (trimmed.isEmpty()) return null;
        String alias = resolveAlias(trimmed);
        if (alias != null && !alias.equals(trimmed)) return alias;
        String typo = didYouMean(trimmed, hotwords);
        if (typo != null && !typo.equals(trimmed)) return typo;
        String norm = normalizeQuery(trimmed);
        if (!norm.isEmpty() && !norm.equals(trimmed)) return norm;
        return null;
    }
}
