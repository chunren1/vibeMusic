package com.vibemusic.service;

import com.vibemusic.config.NeteaseApiConfig;
import jakarta.annotation.PostConstruct;
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

    private String cookie;

    @PostConstruct
    public void init() {
        this.cookie = "MUSIC_U=0071D17677948480F91F9A3B06212445D28775758ADC9B6E5364D09ACE5D47C3CA025B31607583A948EA98505E45C0A57427D623CB2CFD9F6413035EE8B317AF66A5853CCD2FE3F4793DAC192F0A4219184ACA45E79535895101F47DFFDC0125F171DEB696D8B68974211C4FD6740A4F6245A853DA76A4D13E934F1A5AE25CE27AF015287BAF576F654C03445E09F3DA7607D29EBDBF41A4BF037B38BCBFE2888F97F6EC7BF5FC6AE34B9640061B9022F7174A4885054119BB0A42F7B9D5B972ED08231276682B39E30CA2ACC59270E4EB77DE048CEB9992438BF0B7A6F24378736A53B78A1C582CF0FC29363C38D687B947F2C358FF98901A1C4A51327C0CE19E029694DC5A1004B9EF22B3174CF597073192F29987521917637E8E7A72AAC3B171BB0BD5EAB972818FD69B3AC0846EDA27225FE62CF8D6955DEFB9164C40CB8681350BB20871AD2788106404675B58E67F1FCC1BC8DEC7302FD0DEBF1D08C43A54E876C7F7D6DCD681FD916B7FD4ADA97640B2AB721585284A8B718CE2F5E6879BDBE1B5278F6F18407C2BA48E5A23E1BED0A10A29E3D1D77ED16D82594E3ACA";
    }

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
        headers.set("Cookie", cookie);
        headers.set("User-Agent", "Mozilla/5.0");
        return new HttpEntity<>(headers);
    }
}
