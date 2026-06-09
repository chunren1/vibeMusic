package com.vibemusic.service;

import com.vibemusic.config.NeteaseApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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
    private final RestTemplate restTemplate = new RestTemplate();

    // Cookie 已集中到 musicapi/config.js 统一管理，后端不再持有

    public Map<String, Object> search(String keyword, int limit) {
        URI uri = buildUri("/cloudsearch", "keywords", keyword, "limit", String.valueOf(limit));
        log.info("调用网易云搜索: {} (limit={})", keyword, limit);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("搜索 '{}' 返回 status={}", keyword, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getSongUrl(String musicId, String level) {
        URI uri = buildUri("/song/url/v1", "id", musicId, "level", level != null ? level : "exhigh");
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取歌曲 {} URL, level={}, status={}", musicId, level, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getSongDetail(String musicIds) {
        URI uri = buildUri("/song/detail", "ids", musicIds);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        return response.getBody();
    }

    public Map<String, Object> personalizedPlaylists(int limit) {
        URI uri = buildUri("/personalized", "limit", String.valueOf(limit));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildHeaders(), Map.class);
        log.info("获取推荐歌单: limit={}, status={}", limit, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> aggregatedSearch(String keyword, int page, int size) {
        URI uri = buildUri("/search", "keyword", keyword, "page", String.valueOf(page), "size", String.valueOf(size));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        log.info("聚合搜索: {} page={} size={} status={}", keyword, page, size, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> searchNetease(String keyword, int limit) {
        URI uri = buildUri("/netease/search", "keyword", keyword, "limit", String.valueOf(limit));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        return response.getBody();
    }

    public Map<String, Object> searchQQ(String keyword, int limit) {
        URI uri = buildUri("/qq/search", "keyword", keyword, "limit", String.valueOf(limit));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        return response.getBody();
    }

    public Map<String, Object> getLyric(String musicId) {
        URI uri = buildUri("/lyric", "id", musicId);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        log.info("获取歌词: id={} status={}", musicId, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQLyric(String songmid) {
        URI uri = buildUri("/qq/lyric", "songmid", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        log.info("获取QQ歌词: songmid={} status={}", songmid, response.getStatusCode());
        return response.getBody();
    }

    public Map<String, Object> getQQSongUrl(String songmid) {
        URI uri = buildUri("/song/url/qq", "id", songmid);
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        log.info("QQ播放URL: {} status={}", songmid, response.getStatusCode());
        return response.getBody();
    }

    public byte[] downloadSong(String downloadUrl) {
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(downloadUrl, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);
            log.info("下载完成: {} bytes", response.getBody() != null ? response.getBody().length : 0);
            return response.getBody();
        } catch (Exception e) {
            log.error("下载失败: {}", e.getMessage());
            throw new RuntimeException("歌曲下载失败", e);
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

    private HttpEntity<Void> buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // Cookie 已由 musicapi 统一注入，后端不再传递
        headers.set("User-Agent", "Mozilla/5.0");
        return new HttpEntity<>(headers);
    }
}
