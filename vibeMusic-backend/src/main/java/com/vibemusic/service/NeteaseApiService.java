package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NeteaseApiService {

    private final NeteaseApiConfig config;
    private final RestTemplate restTemplate; // 注入连接池版 RestTemplate
    private final RestClient.Builder restClientBuilder;

    /** 流式下载客户端（复用连接池） */
    private RestClient streamClient;

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
        return restTemplate.exchange(uri, HttpMethod.GET, EMPTY_ENTITY, Map.class).getBody();
    }

    public Map<String, Object> searchQQ(String keyword, int limit) {
        URI uri = buildUri("/qq/search", "keyword", keyword, "limit", String.valueOf(limit));
        return restTemplate.exchange(uri, HttpMethod.GET, EMPTY_ENTITY, Map.class).getBody();
    }

    public Map<String, Object> getLyric(String musicId) {
        URI uri = buildUri("/lyric", "id", musicId);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, EMPTY_ENTITY, Map.class);
        log.info("获取歌词: id={} status={}", musicId, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQLyric(String songmid) {
        URI uri = buildUri("/qq/lyric", "songmid", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, EMPTY_ENTITY, Map.class);
        log.info("获取QQ歌词: songmid={} status={}", songmid, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQSongUrl(String songmid) {
        URI uri = buildUri("/song/url/qq", "id", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, EMPTY_ENTITY, Map.class);
        log.info("QQ播放URL: {} status={}", songmid, response.getStatusCode());
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
        try {
            java.io.File tempFile = java.io.File.createTempFile("vibemusic-dl-", ".mp3");
            streamClient.get()
                    .uri(downloadUrl)
                    .exchange((req, resp) -> {
                        try (java.io.InputStream in = resp.getBody();
                             java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        return null;
                    });
            log.info("流式下载完成: {} → {}", downloadUrl, tempFile.getName());
            return tempFile;
        } catch (Exception e) {
            log.error("流式下载失败: {}", e.getMessage());
            throw new BusinessException(502, "歌曲下载失败");
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

    /** 复用 HttpHeaders / HttpEntity，避免每次请求创建 */
    private static final HttpHeaders SHARED_HEADERS = new HttpHeaders();
    private static final HttpEntity<Void> EMPTY_ENTITY = new HttpEntity<>(new HttpHeaders());
    static { SHARED_HEADERS.set("User-Agent", "Mozilla/5.0"); }

    private HttpEntity<Void> buildHeaders() {
        // Cookie 已由 musicapi 统一注入，后端不再传递
        return new HttpEntity<>(SHARED_HEADERS);
    }
}
