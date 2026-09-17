package com.zzj.interview.infrastructure.ai;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Chroma本地向量库客户端（REST）
 *
 * 说明：
 * - Chroma 1.x 仅提供 v2 API（/api/v1 返回410弃用），路径按 租户/数据库/集合 作用域划分：
 *   /api/v2/tenants/{tenant}/databases/{database}/collections/{collectionId}
 * - 集合的 add/upsert/query/count 必须用集合的 UUID，而非集合名；
 *   因此启动时先 get_or_create 解析出 UUID 并缓存。
 * - 距离度量使用 cosine（创建集合时 metadata hnsw:space=cosine）。
 * - Chroma不可用时本组件标记 available=false，上层自动降级，不抛异常打断业务。
 */
@Component
@ConditionalOnProperty(prefix = "ai.chroma", name = "enabled", havingValue = "true")
public class ChromaVectorStore {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorStore.class);

    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";

    private final RestTemplate restTemplate;
    private final ChromaProperties properties;

    /** /api/v2/tenants/default_tenant/databases/default_database */
    private String scopeBase;
    private volatile boolean available = false;
    private volatile String collectionId;

    public ChromaVectorStore(RestTemplate restTemplate, ChromaProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        scopeBase = properties.getBaseUrl()
                + "/api/v2/tenants/" + TENANT + "/databases/" + DATABASE;
        try {
            // 心跳探活
            restTemplate.getForObject(properties.getBaseUrl() + "/api/v2/heartbeat", Map.class);
            collectionId = getOrCreateCollection(properties.getCollection());
            available = true;
            log.info("Chroma向量库就绪：collection={} id={}", properties.getCollection(), collectionId);
        } catch (Exception e) {
            available = false;
            log.warn("Chroma初始化失败，长期记忆降级为内存画像：{}", e.getMessage());
        }
    }

    /** get_or_create 集合，返回集合UUID */
    private String getOrCreateCollection(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("metadata", Map.of("hnsw:space", "cosine"));
        body.put("get_or_create", true);

        Map<?, ?> resp = post(scopeBase + "/collections", body, Map.class);
        Object id = resp != null ? resp.get("id") : null;
        if (id == null) {
            throw new RuntimeException("Chroma返回集合缺少id: " + resp);
        }
        return String.valueOf(id);
    }

    /**
     * 写入/更新一条向量记录（单条upsert，id存在则覆盖）
     */
    public boolean upsert(String id, List<Float> embedding, String document, Map<String, Object> metadata) {
        if (!available) return false;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", List.of(id));
            body.put("embeddings", List.of(embedding));
            body.put("documents", List.of(document != null ? document : ""));
            body.put("metadatas", List.of(metadata != null ? metadata : Map.of()));
            post(scopeBase + "/collections/" + collectionId + "/upsert", body, Map.class);
            return true;
        } catch (Exception e) {
            log.warn("Chroma upsert失败 [{}]：{}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 语义检索：返回 [{document, metadata, distance}]，distance为cosine距离（越小越相似）
     *
     * @param where 元数据过滤，如 {"user_id":"u1"}；可为null
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> query(List<Float> embedding, int nResults, Map<String, Object> where) {
        if (!available || embedding == null || embedding.isEmpty()) return List.of();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_embeddings", List.of(embedding));
            body.put("n_results", nResults);
            body.put("include", List.of("documents", "metadatas", "distances"));
            if (where != null && !where.isEmpty()) {
                body.put("where", where);
            }

            Map<String, Object> resp = post(scopeBase + "/collections/" + collectionId + "/query", body, Map.class);
            if (resp == null) return List.of();

            // 响应为“每个查询向量一组结果”的嵌套数组，这里只查询一条，取索引0
            List<String> ids = firstOf((List<List<String>>) resp.get("ids"));
            List<String> docs = firstOf((List<List<String>>) resp.get("documents"));
            List<Map<String, Object>> metas = firstOf((List<List<Map<String, Object>>>) resp.get("metadatas"));
            List<Double> dists = firstOf((List<List<Double>>) resp.get("distances"));

            List<Map<String, Object>> out = new ArrayList<>();
            int n = ids != null ? ids.size() : 0;
            for (int i = 0; i < n; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", ids.get(i));
                item.put("document", docs != null && i < docs.size() ? docs.get(i) : "");
                item.put("metadata", metas != null && i < metas.size() ? metas.get(i) : Map.of());
                item.put("distance", dists != null && i < dists.size() ? dists.get(i) : 0.0);
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.warn("Chroma query失败：{}", e.getMessage());
            return List.of();
        }
    }

    public long count() {
        if (!available) return 0;
        try {
            Long c = restTemplate.getForObject(scopeBase + "/collections/" + collectionId + "/count", Long.class);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private static <T> List<T> firstOf(List<List<T>> nested) {
        return (nested != null && !nested.isEmpty()) ? nested.get(0) : null;
    }

    private <T> T post(String url, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<T> resp = restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
        return resp.getBody();
    }
}
