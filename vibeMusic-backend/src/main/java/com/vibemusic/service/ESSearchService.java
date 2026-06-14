package com.vibemusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * ES 搜索缓存服务 — 绕过 Spring Data ES 版本冲突，纯 HTTP REST API
 * 索引: POST /search_cache/_doc  查询: GET /search_cache/_search?q=keyword:xxx
 */
@Slf4j
@Service
public class ESSearchService {

    private final WebClient client;
    private final ObjectMapper mapper;
    private static final String INDEX = "search_cache";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public ESSearchService(@Value("${spring.elasticsearch.uris:http://localhost:9201}") String esUris,
                           ObjectMapper mapper) {
        this.client = WebClient.builder().baseUrl(esUris).build();
        this.mapper = mapper;
        createIndexIfNeeded();
    }

    private void createIndexIfNeeded() {
        try {
            client.head().uri("/" + INDEX)
                    .retrieve().toBodilessEntity()
                    .timeout(TIMEOUT)
                    .onErrorResume(e -> {
                        log.info("ES 索引不存在，创建中...");
                        String body = """
                            {"settings":{"number_of_shards":1,"number_of_replicas":0},
                             "mappings":{"properties":{"keyword":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                             "songId":{"type":"keyword"},"name":{"type":"text","index":false},
                             "artist":{"type":"text","index":false},"album":{"type":"text","index":false},
                             "coverUrl":{"type":"keyword","index":false},"duration":{"type":"integer"},
                             "source":{"type":"keyword"},"finalScore":{"type":"double"},
                             "createTime":{"type":"date"}}}}""";
                        return client.put().uri("/" + INDEX)
                                .header("Content-Type", "application/json")
                                .bodyValue(body)
                                .retrieve().toBodilessEntity();
                    })
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("ES 不可用（服务未启动？），搜索缓存降级: {}", e.getMessage());
        }
    }

    /** 批量保存搜索结果到 ES */
    public void indexSearchResults(String keyword, List<SongDTO> songs) {
        if (songs == null || songs.isEmpty()) return;
        try {
            StringBuilder bulk = new StringBuilder();
            for (SongDTO s : songs) {
                String id = md5(keyword + s.getSourceId());
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("keyword", keyword);
                doc.put("songId", s.getSourceId());
                doc.put("name", s.getName());
                doc.put("artist", s.getArtist());
                doc.put("album", s.getAlbum());
                doc.put("coverUrl", s.getCoverUrl());
                doc.put("duration", s.getDuration());
                doc.put("source", s.getPlatform());
                doc.put("finalScore", s.getFinalScore());
                doc.put("createTime", new Date());
                bulk.append("{\"index\":{\"_index\":\"").append(INDEX).append("\",\"_id\":\"").append(id).append("\"}}\n");
                bulk.append(mapper.writeValueAsString(doc)).append("\n");
            }
            client.post().uri("/_bulk")
                    .header("Content-Type", "application/x-ndjson")
                    .bodyValue(bulk.toString())
                    .retrieve().toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.debug("ES 索引写入失败（服务未就绪）: {}", e.getMessage());
        }
    }

    /** 从 ES 搜索缓存的歌曲 */
    public List<SongDTO> searchCached(String keyword) {
        try {
            String resp = client.get()
                    .uri("/" + INDEX + "/_search?q=keyword:" + keyword + "&size=80")
                    .retrieve().bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();
            if (resp == null) return List.of();
            JsonNode hits = mapper.readTree(resp).path("hits").path("hits");
            List<SongDTO> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode src = hit.path("_source");
                SongDTO dto = new SongDTO();
                dto.setSourceId(src.path("songId").asText());
                dto.setName(src.path("name").asText());
                dto.setArtist(src.path("artist").asText());
                dto.setAlbum(src.path("album").asText());
                dto.setCoverUrl(src.path("coverUrl").asText());
                dto.setDuration(src.path("duration").asInt());
                dto.setPlatform(src.path("source").asText());
                dto.setFinalScore(src.path("finalScore").asDouble());
                result.add(dto);
            }
            if (!result.isEmpty()) log.info("ES 缓存命中: '{}' count={}", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.debug("ES 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
