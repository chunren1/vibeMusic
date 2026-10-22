package com.vibemusic.common.utils;

import java.util.regex.Pattern;

/**
 * 前端错误上报的二次脱敏（P0-7 后端侧）。
 *
 * <p>前端已经做过一次脱敏，但浏览器侧的数据<b>不完全可信</b>：UA、堆栈、第三方脚本的 message
 * 都可能夹带邮箱、手机号、URL query 中的 token。落日志前必须再过一遍，否则等于把用户隐私
 * 写进 {@code docker logs}（而这些日志会被异地备份、人工排查翻看）。</p>
 *
 * <p>顺序：控制字符 → 敏感 query → Bearer/长十六进制串 → 邮箱 → 手机号 → 家目录用户名。</p>
 */
public final class ClientErrorScrubber {

    /** 控制字符（含换行）：日志注入与多行伪造的源头，统一替换成空格（保留制表符）。 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    /** URL query 里的敏感键。 */
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:token|access_token|refresh_token|code|key|password|pwd|secret|sign|signature)=)[^&\\s\"'<>]+");

    /** Authorization: Bearer xxx。 */
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=\\-]{8,}");

    /** 32+ 位十六进制（md5 / 签名 / API key 片段）。 */
    private static final Pattern LONG_HEX = Pattern.compile("\\b[a-fA-F0-9]{32,}\\b");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** 中国大陆手机号（前后不接数字，避免误伤订单号）。 */
    private static final Pattern CN_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 家目录：只保留路径分隔符，抹掉用户名。 */
    private static final Pattern HOME_DIR = Pattern.compile(
            "((?:/home/|/Users/|[A-Za-z]:\\\\Users\\\\))([^\\s/\\\\]+)");

    private ClientErrorScrubber() {
    }

    /**
     * @param raw 原始文本，可为 null
     * @return 脱敏后的文本；null 输入返回 null
     */
    public static String scrub(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String s = CONTROL_CHARS.matcher(raw).replaceAll(" ");
        s = QUERY_SECRET.matcher(s).replaceAll("$1<redacted>");
        s = BEARER.matcher(s).replaceAll("$1<redacted>");
        s = LONG_HEX.matcher(s).replaceAll("<hex>");
        s = EMAIL.matcher(s).replaceAll("<email>");
        s = CN_PHONE.matcher(s).replaceAll("<phone>");
        s = HOME_DIR.matcher(s).replaceAll("$1<user>");
        return s;
    }

    /**
     * 路由只保留 pathname：前端理论上已经裁过，这里再兜一次
     * （防止有人直接把完整 URL 塞进来，把 query 里的东西带进日志）。
     */
    public static String pathOnly(String route) {
        if (route == null) {
            return null;
        }
        String s = route.trim();
        int cut = s.length();
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        s = s.substring(0, cut);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash >= 0 ? s.substring(slash) : "/";
        }
        return s;
    }
}
