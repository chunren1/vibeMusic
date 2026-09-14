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

    @Value("${stream.cdn-whitelist:*.music.126.net,*.gtimg.cn,*.stream.qqmusic.qq.com,*.tc.qq.com,*.tencentmusic.com,*.migu.cn}")
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

    public Map<String, Object> getSongUrl(String musicId, String level) {
        URI uri = buildUri("/song/url/v1", "id", musicId, "level", level != null ? level : "exhigh");
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
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
        URI uri = buildUri("/netease/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class).getBody();
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
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        return new HttpEntity<>(headers);
    }
}
