package com.vibemusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ES 搜索缓存服务 — 等价于 Spring Data Repository 的功能
 * 用 WebClient 替代 elasticsearch-java 9.x，绕过与 ES 8.x 的断代兼容问题
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
        ensureIndex();
    }

    // ========== 对外 API（等价 Repository 方法签名） ==========

    /** repository.saveAll() — 批量写入 */
    public void saveAll(List<SongDTO> songs, String keyword) {
        if (songs == null || songs.isEmpty()) return;
        try {
            StringBuilder bulk = new StringBuilder();
            for (SongDTO s : songs) {
                String id = md5(keyword + s.getSourceId());
                bulk.append("{\"index\":{\"_index\":\"").append(INDEX).append("\",\"_id\":\"").append(id).append("\"}}\n");
                bulk.append(mapper.writeValueAsString(toDoc(keyword, s))).append("\n");
            }
            client.post().uri("/_bulk").header("Content-Type", "application/x-ndjson")
                    .bodyValue(bulk.toString()).retrieve().toBodilessEntity().timeout(TIMEOUT).block();
        } catch (Exception e) {
            log.debug("ES 写入失败: {}", e.getMessage());
        }
    }

    /** repository.findByKeyword() — IK 分词搜索 */
    public List<SongDTO> findByKeyword(String keyword) {
        try {
            String resp = client.get().uri(uri -> uri.path("/" + INDEX + "/_search")
                            .queryParam("q", "keyword:" + keyword).queryParam("size", 80).build())
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
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
            if (!result.isEmpty()) log.info("ES 命中: '{}' count={}", keyword, result.size());
            return result;
        } catch (Exception e) {
            log.debug("ES 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** repository.deleteByCreateTimeBefore() — 清理过期数据 */
    public void deleteByCreateTimeBefore(Date expireTime) {
        try {
            String query = "{\"query\":{\"range\":{\"createTime\":{\"lt\":\"" +
                    expireTime.toInstant().toString() + "\"}}}}";
            client.post().uri("/" + INDEX + "/_delete_by_query")
                    .header("Content-Type", "application/json").bodyValue(query)
                    .retrieve().toBodilessEntity().timeout(TIMEOUT).block();
            log.info("ES 清理过期数据: before {}", expireTime);
        } catch (Exception e) {
            log.debug("ES 清理失败: {}", e.getMessage());
        }
    }

    /** 兼容 SongService 旧调用 */
    public void indexSearchResults(String keyword, List<SongDTO> songs) {
        saveAll(songs, keyword);
    }

    public List<SongDTO> searchCached(String keyword) {
        return findByKeyword(keyword);
    }

    // ========== 内部 ==========

    private void ensureIndex() {
        try {
            client.head().uri("/" + INDEX).retrieve().toBodilessEntity()
                    .timeout(TIMEOUT).onErrorResume(e -> {
                        String body = """
                    {"settings":{"number_of_shards":1,"number_of_replicas":0},
                     "mappings":{"properties":{"keyword":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                     "songId":{"type":"keyword"},"name":{"type":"text","index":false},"artist":{"type":"text","index":false},
                     "album":{"type":"text","index":false},"coverUrl":{"type":"keyword","index":false},
                     "duration":{"type":"integer"},"source":{"type":"keyword"},"finalScore":{"type":"double"},
                     "createTime":{"type":"date"}}}}""";
                        return client.put().uri("/" + INDEX).header("Content-Type", "application/json")
                                .bodyValue(body).retrieve().toBodilessEntity();
                    }).block(TIMEOUT);
        } catch (Exception e) {
            log.warn("ES 不可用，搜索缓存降级: {}", e.getMessage());
        }
    }

    private Map<String, Object> toDoc(String keyword, SongDTO s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("keyword", keyword);       doc.put("songId", s.getSourceId());
        doc.put("name", s.getName());       doc.put("artist", s.getArtist());
        doc.put("album", s.getAlbum());     doc.put("coverUrl", s.getCoverUrl());
        doc.put("duration", s.getDuration()); doc.put("source", s.getPlatform());
        doc.put("finalScore", s.getFinalScore()); doc.put("createTime", new Date());
        return doc;
    }

    private static String md5(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
