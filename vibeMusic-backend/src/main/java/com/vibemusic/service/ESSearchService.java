package com.vibemusic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.dto.SearchResponse;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ES 搜索缓存服务 — 等价于 Spring Data Repository 的功能
 * 用 WebClient 替代 elasticsearch-java 9.x，绕过与 ES 8.x 的断代兼容问题
 *
 * 容错设计：
 * - 构造时异步建索引（不阻塞启动）
 * - 所有 ES 操作都有 try-catch + 超时保护
 * - ES 不可用时自动降级，isAvailable() 返回 false
 * - 写入失败不抛异常，仅日志记录
 */
@Slf4j
@Service
public class ESSearchService {

    private final WebClient client;
    private final ObjectMapper mapper;
    private static final String INDEX = "search_cache";
    private static final Duration TIMEOUT = Duration.ofSeconds(3); // 容错：3s 超时
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(1); // 健康检查 1s
    private final AtomicBoolean available = new AtomicBoolean(false);

    public ESSearchService(@Value("${spring.elasticsearch.uris:http://localhost:9201}") String esUris,
                           ObjectMapper mapper) {
        this.client = WebClient.builder().baseUrl(esUris).build();
        this.mapper = mapper;
        // 异步初始化，不阻塞 Spring Boot 启动
        Thread initThread = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {} // 等 ES 容器就绪
            ensureIndex();
        }, "es-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    // ========== 健康检查 ==========

    /** 查询 ES 集群健康状态，返回 null=不可用 */
    public Map<String, Object> healthCheck() {
        try {
            String resp = client.get().uri("/_cluster/health")
                    .retrieve().bodyToMono(String.class)
                    .timeout(HEALTH_TIMEOUT).block();
            if (resp == null) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> health = mapper.readValue(resp, Map.class);
            // 更新可用性标记
            String status = (String) health.get("status");
            available.set(!"red".equals(status));
            return health;
        } catch (Exception e) {
            available.set(false);
            return null;
        }
    }

    /** 轻量级可用性检查 */
    public boolean isAvailable() {
        if (!available.get()) {
            try {
                var resp = client.head().uri("/").retrieve()
                        .toBodilessEntity()
                        .timeout(HEALTH_TIMEOUT).block();
                if (resp != null && resp.getStatusCode().is2xxSuccessful())
                    available.set(true);
            } catch (Exception ignored) {}
        }
        return available.get();
    }

    // ========== 对外 API（等价 Repository 方法签名） ==========

    /** repository.saveAll() — 批量写入（容错：失败不影响返回） */
    public void saveAll(List<SongDTO> songs, String keyword) {
        if (songs == null || songs.isEmpty()) return;
        if (!isAvailable()) {
            log.warn("[ES-LAYER] 批量写入跳过（ES 不可用）: keyword='{}', count={}", keyword, songs.size());
            return;
        }
        long start = System.currentTimeMillis();
        try {
            StringBuilder bulk = new StringBuilder();
            for (SongDTO s : songs) {
                String id = md5(keyword + s.getSourceId());
                bulk.append("{\"index\":{\"_index\":\"").append(INDEX).append("\",\"_id\":\"").append(id).append("\"}}\n");
                bulk.append(mapper.writeValueAsString(toDoc(keyword, s))).append("\n");
            }
            byte[] body = bulk.toString().getBytes(StandardCharsets.UTF_8);

            // 使用 WebClient 统一发送 ndjson，纳入连接池管理
            String resp = client.post().uri("/_bulk")
                    .header("Content-Type", "application/x-ndjson")
                    .bodyValue(body)
                    .retrieve().bodyToMono(String.class)
                    .timeout(TIMEOUT).block();

            if (resp != null) {
                JsonNode root = mapper.readTree(resp);
                if (root.path("errors").asBoolean()) {
                    long errCount = 0;
                    for (JsonNode item : root.path("items")) {
                        if (item.path("index").path("error").isObject()) {
                            errCount++;
                            if (errCount <= 3) {
                                log.warn("[ES-LAYER] 写入错误详情: id={}, reason={}",
                                        item.path("index").path("_id").asText(),
                                        item.path("index").path("error").path("reason").asText());
                            }
                        }
                    }
                    log.warn("[ES-LAYER] 批量写入部分失败: keyword='{}', errors={}, total={}",
                            keyword, errCount, songs.size());
                }
            }
            long cost = System.currentTimeMillis() - start;
            log.info("[ES-LAYER] 批量写入成功: keyword='{}', count={}, cost={}ms", keyword, songs.size(), cost);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ES-LAYER] 批量写入失败（不影响返回结果）: keyword='{}', count={}, cost={}ms, error={}",
                    keyword, songs.size(), cost, e.getMessage());
            available.set(false);
        }
    }

    /** repository.findByKeyword() — IK 分词搜索（容错：失败返回空列表，自动降级） */
    public List<SongDTO> findByKeyword(String keyword) {
        if (!isAvailable()) {
            log.info("[ES-LAYER] 搜索跳过（ES 不可用，自动降级到 API）: keyword='{}'", keyword);
            return List.of();
        }
        long start = System.currentTimeMillis();
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
            long cost = System.currentTimeMillis() - start;
            if (!result.isEmpty()) {
                log.info("[ES-LAYER] 命中: keyword='{}', count={}, cost={}ms", keyword, result.size(), cost);
            } else {
                log.info("[ES-LAYER] 未命中: keyword='{}', cost={}ms", keyword, cost);
            }
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ES-LAYER] 查询失败（自动降级到 API）: keyword='{}', cost={}ms, error={}",
                    keyword, cost, e.getMessage());
            available.set(false); // 标记不可用，下次自动重检
            return List.of();
        }
    }

    /** repository.deleteByCreateTimeBefore() — 清理过期数据 */
    public void deleteByCreateTimeBefore(Date expireTime) {
        if (!isAvailable()) {
            log.warn("[ES-LAYER] 清理跳过（ES 不可用）");
            return;
        }
        try {
            String query = "{\"query\":{\"range\":{\"createTime\":{\"lt\":\"" +
                    expireTime.toInstant().toString() + "\"}}}}";
            String resp = client.post().uri("/" + INDEX + "/_delete_by_query")
                    .header("Content-Type", "application/json").bodyValue(query)
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            if (resp != null) {
                JsonNode root = mapper.readTree(resp);
                long deleted = root.path("deleted").asLong();
                log.info("[ES-LAYER] 清理过期数据: before={}, deleted={}", expireTime, deleted);
            }
        } catch (Exception e) {
            log.warn("[ES-LAYER] 清理失败: error={}", e.getMessage());
            available.set(false);
        }
    }

    /** 兼容 SongService 旧调用 */
    public void indexSearchResults(String keyword, List<SongDTO> songs) {
        saveAll(songs, keyword);
    }

    public List<SongDTO> searchCached(String keyword) {
        return findByKeyword(keyword);
    }

    // ========== 搜索增强 API ==========

    /** 带高亮 + 平台聚合的搜索（容错：失败返回空Response） */
    public SearchResponse searchWithHighlight(String keyword) {
        if (!isAvailable()) {
            log.info("[ES-LAYER] 增强搜索跳过（ES 不可用）: keyword='{}'", keyword);
            return SearchResponse.builder().songs(List.of()).build();
        }
        long start = System.currentTimeMillis();
        try {
            String body = String.format("""
                {"query":{"match":{"keyword":"%s"}},
                 "highlight":{"fields":{"keyword":{}},"pre_tags":["<em>"],"post_tags":["</em>"]},
                 "aggs":{"by_source":{"terms":{"field":"source"}}},
                 "size":80}""", keyword);
            String resp = client.post().uri("/" + INDEX + "/_search")
                    .header("Content-Type", "application/json").bodyValue(body)
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            if (resp == null) return SearchResponse.builder().songs(List.of()).build();

            JsonNode root = mapper.readTree(resp);
            JsonNode hits = root.path("hits").path("hits");

            List<SongDTO> songs = new ArrayList<>();
            Map<String, List<String>> highlights = new HashMap<>();

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
                songs.add(dto);

                // 解析高亮
                JsonNode hl = hit.path("highlight").path("keyword");
                if (hl.isArray()) {
                    List<String> frags = new ArrayList<>();
                    hl.forEach(f -> frags.add(f.asText()));
                    highlights.put(dto.getSourceId(), frags);
                }
            }

            // 解析聚合
            Map<String, Long> counts = new HashMap<>();
            JsonNode buckets = root.path("aggregations").path("by_source").path("buckets");
            for (JsonNode b : buckets) {
                counts.put(b.path("key").asText(), b.path("doc_count").asLong());
            }

            long cost = System.currentTimeMillis() - start;
            log.info("[ES-LAYER] 增强搜索: keyword='{}', hits={}, cost={}ms", keyword, songs.size(), cost);
            return SearchResponse.builder().songs(songs).highlights(highlights).platformCounts(counts).build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("[ES-LAYER] 增强搜索失败（自动降级）: keyword='{}', cost={}ms, error={}",
                    keyword, cost, e.getMessage());
            available.set(false);
            return SearchResponse.builder().songs(List.of()).build();
        }
    }

    /** 搜索建议（补全） */
    public List<String> suggest(String prefix) {
        if (!isAvailable()) return List.of();
        try {
            String body = String.format("""
                {"suggest":{"song-suggest":{"prefix":"%s","completion":{"field":"keyword.suggest","size":8}}}}""", prefix);
            String resp = client.post().uri("/" + INDEX + "/_search")
                    .header("Content-Type", "application/json").bodyValue(body)
                    .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            if (resp == null) return List.of();

            List<String> result = new ArrayList<>();
            JsonNode opts = mapper.readTree(resp).path("suggest").path("song-suggest").get(0).path("options");
            for (JsonNode o : opts) result.add(o.path("text").asText());
            return result;
        } catch (Exception e) {
            available.set(false);
            return List.of();
        }
    }

    // ========== 内部 ==========

    private void ensureIndex() {
        try {
            // 先检查 ES 是否可达（用 head 请求，不读 body，避免超时后 Netty 响应体释放竞态）
            var resp = client.head().uri("/").retrieve()
                    .toBodilessEntity()
                    .timeout(HEALTH_TIMEOUT)
                    .block();
            if (resp == null || !resp.getStatusCode().is2xxSuccessful()) {
                log.warn("[ES-LAYER] 初始化跳过：ES 服务不可达");
                available.set(false);
                return;
            }

            client.head().uri("/" + INDEX).retrieve().toBodilessEntity()
                    .timeout(TIMEOUT).onErrorResume(e -> {
                        log.info("[ES-LAYER] 索引 '{}' 不存在，自动创建（IK 分词 + completion suggest）", INDEX);
                        String body = """
                    {"settings":{"number_of_shards":1,"number_of_replicas":0},
                     "mappings":{"properties":{"keyword":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart",
                      "fields":{"suggest":{"type":"completion","analyzer":"ik_max_word"}}},
                     "songId":{"type":"keyword"},"name":{"type":"text","index":false},"artist":{"type":"text","index":false},
                     "album":{"type":"text","index":false},"coverUrl":{"type":"keyword","index":false},
                     "duration":{"type":"integer"},"source":{"type":"keyword"},"finalScore":{"type":"double"},
                     "createTime":{"type":"date"}}}}""";
                        return client.put().uri("/" + INDEX).header("Content-Type", "application/json")
                                .bodyValue(body).retrieve().toBodilessEntity();
                    }).block(TIMEOUT);
            available.set(true);
            log.info("[ES-LAYER] 初始化完成，索引 '{}' 就绪", INDEX);
        } catch (Exception e) {
            available.set(false);
            log.warn("[ES-LAYER] 初始化失败（搜索缓存将自动跳过ES层）: {}", e.getMessage());
        }
    }

    private Map<String, Object> toDoc(String keyword, SongDTO s) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("keyword", keyword);       doc.put("songId", s.getSourceId());
        doc.put("name", s.getName());       doc.put("artist", s.getArtist());
        doc.put("album", s.getAlbum());     doc.put("coverUrl", s.getCoverUrl());
        doc.put("duration", s.getDuration()); doc.put("source", s.getPlatform());
        doc.put("finalScore", s.getFinalScore()); doc.put("createTime", java.time.Instant.now().toString());
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
