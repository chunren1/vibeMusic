package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.common.utils.StreamUtils;
import com.vibemusic.config.NeteaseApiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
@RequiredArgsConstructor
public class NeteaseApiService {

    private final NeteaseApiConfig config;
    private final RestTemplate restTemplate; // 注入连接池版 RestTemplate
    private final RestClient.Builder restClientBuilder;

    /** 流式下载客户端（复用连接池） */
    private RestClient streamClient;

    @Value("${stream.cdn-whitelist:*.music.126.net,*.gtimg.cn,*.stream.qqmusic.qq.com,*.tc.qq.com,*.tencentmusic.com,*.migu.cn,*.bilivideo.com,*.hdslb.com}")
    private String cdnWhitelistConfig;

    private boolean isUrlAllowed(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            List<String> whitelist = Arrays.stream(cdnWhitelistConfig.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            for (String pattern : whitelist) {
                if (pattern.startsWith("*.")) {
                    String suffix = pattern.substring(1);
                    if (host.equals(pattern.substring(2)) || host.endsWith(suffix)) return true;
                } else if (host.equals(pattern)) return true;
            }
            log.warn("SSRF blocked in NeteaseApiService: host={} url={}", host, url);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @PostConstruct
    void initStreamClient() {
        this.streamClient = restClientBuilder.build();
    }

    // Cookie 已集中到 musicapi/config.js 统一管理，后端不再持有
    //
    // BYOC（Bring Your Own Cookie）：per-user 网易云 Cookie 经
    // X-Vibe-User-Cookie 请求头透传给 musicapi 网关（见 musicapi/src/cookie.js
    // resolveNeteaseCookie）。优先级（网关侧执行）：per-request header →
    // 共享 NETEASE_COOKIE → 匿名。本类只负责透传，不做任何有效性判定；
    // header 缺席 = 回落共享/匿名，与旧行为完全一致。

    /** musicapi 网关采信的 per-request 用户 Cookie 请求头（仅内网可信网络）。 */
    public static final String USER_COOKIE_HEADER = "X-Vibe-User-Cookie";

    /**
     * need-login 判定（BYOC Task 7：过期/失效探测）。
     *
     * <p>已核验的信号形状（网关 {@code GET /song/url/v1} 为
     * {@code NeteaseCloudMusicApi.song_url_v1} 原样透传，见
     * {@code musicapi/src/routes.js}，响应形为 {@code {code, data:[{id,url,…}]}}）：
     * <ul>
     *   <li>顶层 {@code code} 为 301（需登录）或 -101（账号异常/需登录）；</li>
     *   <li>顶层 {@code message} 含“需要登录”/“need login”/“login required”（大小写不敏感）；</li>
     *   <li>{@code /login/status} 形 {@code {account:{anonymous:true}}}（匿名态=登录失效）。</li>
     * </ul>
     * <p>明确的<b>非</b>信号：{@code code=200 + data[0].url=null} 只是无版权，
     * 绝不能标记失效（否则会误删有效 Cookie）。
     *
     * @param body 网关返回的响应体（/song/url/v1 或 /login/status 原样透传）
     * @return true=登录失效，调用方应调 {@code UserService#markNeteaseCookieInvalid}
     */
    public static boolean isNeedLoginPayload(Map<String, Object> body) {
        if (body == null) return false;
        if (isNeedLoginCode(body.get("code"))) return true;
        Object message = body.get("message");
        if (message instanceof String text && isNeedLoginMessage(text)) return true;
        Object account = body.get("account");
        if (account instanceof Map<?, ?> accountMap
                && Boolean.TRUE.equals(accountMap.get("anonymous"))) return true;
        return false;
    }

    /**
     * 提取上游顶层 code，仅供失效日志（userId + code）使用。
     *
     * @param body 网关响应体，可能为 null
     * @return code 原值（Number/String），缺失时为 null
     */
    public static Object extractUpstreamCode(Map<String, Object> body) {
        return body == null ? null : body.get("code");
    }

    private static boolean isNeedLoginCode(Object code) {
        if (code instanceof Number number) {
            int value = number.intValue();
            return value == 301 || value == -101;
        }
        if (code instanceof String text) {
            String trimmed = text.strip();
            return "301".equals(trimmed) || "-101".equals(trimmed);
        }
        return false;
    }

    private static boolean isNeedLoginMessage(String message) {
        if (message.contains("需要登录")) return true;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("need login") || lower.contains("login required");
    }

    public Map<String, Object> getSongUrl(String musicId, String level) {
        return getSongUrl(musicId, level, null);
    }

    public Map<String, Object> getSongUrl(String musicId, String level, String userCookie) {
        URI uri = buildUri("/song/url/v1", "id", musicId, "level", level != null ? level : "exhigh");
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(userCookie), Map.class);
        log.info("获取歌曲 {} URL, level={}, status={}", musicId, level, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> personalizedPlaylists(int limit) {
        URI uri = buildUri("/personalized", "limit", String.valueOf(limit));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取推荐歌单: limit={}, status={}", limit, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getNeteasePlaylist(String id) {
        URI uri = buildUri("/netease/playlist_detail", "id", id);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取网易云歌单: id={}, status={}", id, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQPlaylist(String id) {
        URI uri = buildUri("/qq/playlist", "id", id);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取QQ歌单: id={}, status={}", id, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> searchNetease(String keyword, int limit) {
        return searchNetease(keyword, limit, null);
    }

    public Map<String, Object> searchNetease(String keyword, int limit, String userCookie) {
        URI uri = buildUri("/netease/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(userCookie), Map.class).getBody();
    }

    public Map<String, Object> searchQQ(String keyword, int limit) {
        URI uri = buildUri("/qq/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class).getBody();
    }

    public Map<String, Object> searchMigu(String keyword, int limit) {
        URI uri = buildUri("/migu/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class).getBody();
    }

    public Map<String, Object> getLyric(String musicId) {
        URI uri = buildUri("/lyric", "id", musicId);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取歌词: id={} status={}", musicId, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQLyric(String songmid) {
        URI uri = buildUri("/qq/lyric", "songmid", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取QQ歌词: songmid={} status={}", songmid, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQSongUrl(String songmid) {
        URI uri = buildUri("/song/url/qq", "id", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("QQ播放URL: {} status={}", songmid, response.getStatusCode());
        return response.getBody();
    }

    /**
     * 咪咕播放地址：musicapi 返回 302 Location 签名 URL 字符串（IP-bound，
     * 由 StreamController 代理字节流，绝不直传 app）；无版权时 url=null。
     */
    public Map<String, Object> getMiguSongUrl(String contentId, String copyrightId) {
        URI uri = buildUri("/migu/url", "id", contentId,
                "copyrightId", copyrightId != null ? copyrightId : "");
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("Migu播放URL: {} status={}", contentId, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> searchKugou(String keyword, int limit) {
        URI uri = buildUri("/kugou/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class).getBody();
    }

    /**
     * 酷狗播放地址（phase 1 匿名直通，不登录/VIP）。
     * 网关 /kugou/url 以 getdata 风格向上游取链：
     * {@code https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=&album_id=&appid=1014}，
     * 无签名 crypto（v5/url 旋转 salt 留待 phase 2）。
     * 音质档：low→128k 默认；standard→320hash（缺失则网关回落 128k）；
     * high/super 为 VIP 档，phase 1 无登录链路，直接返回 null 且不请求上游。
     */
    public Map<String, Object> getKugouSongUrl(String hash, String albumId, String level) {
        String tier = level != null ? level.trim().toLowerCase() : "low";
        if ("high".equals(tier) || "super".equals(tier) || "sq".equals(tier)
                || "hires".equals(tier) || "lossless".equals(tier)) {
            log.info("KuGou播放URL: hash={} tier={} 为VIP档，phase1直接返回null", hash, tier);
            return null;
        }
        if (!"standard".equals(tier)) tier = "low";
        URI uri = buildUri("/kugou/url", "hash", hash,
                "albumId", albumId != null ? albumId : "", "level", tier);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("KuGou播放URL: {} level={} status={}", hash, tier, response.getStatusCode());
        return response.getBody();
    }

    /**
     * 酷狗歌词：网关 /kugou/lyric 复用 getdata 响应内联 lyrics 字段，
     * 返回与 /qq/lyric 同形 {code, data:{lyric}}。
     */
    public Map<String, Object> getKugouLyric(String hash, String albumId) {
        URI uri = buildUri("/kugou/lyric", "hash", hash,
                "albumId", albumId != null ? albumId : "");
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取KuGou歌词: hash={} status={}", hash, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> searchBili(String keyword, int limit) {
        URI uri = buildUri("/bili/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class).getBody();
    }

    /**
     * B站 guest 播放地址（phase 1 匿名，无登录/VIP）。
     * 网关 /bili/url 内部 pagelist→cid→WBI playurl，取 DASH 伴音轨
     * (guest 132–192k AAC)；id 接受纯 bvid 或复合 bvid|cid。
     * 注：无 getBiliLyric——视频字幕需登录(player/v2 对匿名返回空)，
     * 无 guest trivial 歌词通道，故本 phase 不提供。
     */
    public Map<String, Object> getBiliSongUrl(String biliId) {
        URI uri = buildUri("/bili/url", "id", biliId);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("Bili播放URL: {} status={}", biliId, response.getStatusCode());
        return response.getBody();
    }

    /**
     * 流式下载歌曲到临时文件（避免全量加载到内存）
     * <p>
     * 调用方负责在 finally 中删除临时文件。
     *
     * @return 临时文件
     */
    public java.io.File downloadSongToFile(String downloadUrl) {
        if (!isUrlAllowed(downloadUrl)) {
            throw new BusinessException(403, "下载链接不在白名单内，已阻止");
        }
        java.io.File tempFile = null;
        try {
            tempFile = java.io.File.createTempFile("vibemusic-dl-", ".mp3");
            final java.io.File target = tempFile;
            streamClient.get()
                    .uri(URI.create(downloadUrl))
                    .exchange((req, resp) -> {
                        if (resp.getStatusCode().isError()) {
                            throw new BusinessException(502,
                                    "CDN 返回异常状态: " + resp.getStatusCode().value());
                        }
                        try (java.io.InputStream in = resp.getBody();
                             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                            StreamUtils.copy(in, out);
                        }
                        return null;
                    });
            log.info("流式下载完成: {} → {}", downloadUrl, tempFile.getName());
            return tempFile;
        } catch (Exception e) {
            log.error("流式下载失败: {}", e.getMessage());
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("临时文件删除失败: {}", tempFile.getAbsolutePath());
            }
            BusinessException be = new BusinessException(502, "歌曲下载失败: " + e.getMessage());
            be.initCause(e);
            throw be;
        }
    }

    private URI buildUri(String path, String... keyValues) {
        StringBuilder sb = new StringBuilder(config.getBaseUrl()).append(path).append('?');
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(keyValues[i]).append('=');
            sb.append(URLEncoder.encode(keyValues[i + 1], StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private static final String USER_AGENT = "Mozilla/5.0";

    // 每次调用新建 HttpHeaders（HttpHeaders 非线程安全，static 会导致并发修改异常）
    private HttpEntity<Void> buildHeaders() {
        return buildHeaders(null);
    }

    private HttpEntity<Void> buildHeaders(String userCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        if (userCookie != null && !userCookie.isBlank()) {
            headers.set(USER_COOKIE_HEADER, userCookie);
        }
        return new HttpEntity<>(headers);
    }
}
