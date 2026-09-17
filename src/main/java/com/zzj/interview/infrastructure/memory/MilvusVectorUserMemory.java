package com.zzj.interview.infrastructure.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zzj.interview.domain.memory.UserMemory;
import com.zzj.interview.infrastructure.ai.EmbeddingService;
import com.zzj.interview.infrastructure.ai.MilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于Milvus的向量长期记忆实现
 *
 * 设计思路：
 * 1. 用户画像（profile）仍保存在内存中（快速读取）
 * 2. 用户历史交互记录存入Milvus向量库（语义检索）
 * 3. 查询时合并：最近画像 + 语义相关的历史交互
 * 4. 支持按用户ID过滤，实现个性化长期记忆
 *
 * 降级策略：
 * - 当Milvus不可用时，自动降级为纯内存画像
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "ai.milvus", name = "enabled", havingValue = "true")
public class MilvusVectorUserMemory implements UserMemory {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorUserMemory.class);

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusProperties properties;
    private final InMemoryUserMemory fallback;

    private volatile boolean available = false;

    @Autowired
    public MilvusVectorUserMemory(@Autowired(required = false) MilvusClientV2 milvusClient,
                                   EmbeddingService embeddingService,
                                   MilvusProperties properties,
                                   InMemoryUserMemory fallback) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.fallback = fallback;
    }

    @PostConstruct
    public void init() {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，长期记忆降级为内存画像");
            return;
        }
        try {
            ensureCollection();
            available = true;
            log.info("Milvus长期记忆初始化成功，集合：{}", properties.getMemoryCollection());
        } catch (Exception e) {
            log.warn("Milvus长期记忆初始化失败，降级为内存：{}", e.getMessage());
            available = false;
        }
    }

    private void ensureCollection() {
        String coll = properties.getMemoryCollection();
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(coll).build());
        if (exists) return;

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("user_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content").dataType(DataType.VarChar).maxLength(2048).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("intent").dataType(DataType.VarChar).maxLength(32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("session_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("ts").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector).dimension(properties.getEmbeddingDimension()).build());

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .build());

        CreateCollectionReq req = CreateCollectionReq.builder()
                .collectionName(coll)
                .description("用户长期记忆-语义向量")
                .collectionSchema(schema)
                .indexParams(indexes)
                .build();
        milvusClient.createCollection(req);
        log.info("创建Milvus长期记忆集合：{}", coll);
    }

    @Override
    public void save(String userId, UserMemoryData data) {
        fallback.save(userId, data);
        // 画像保存后，将摘要作为一条长期记忆写入Milvus
        if (!available || milvusClient == null || data.lastSummary() == null || data.lastSummary().isBlank()) {
            return;
        }
        try {
            JsonObject row = new JsonObject();
            row.addProperty("user_id", userId);
            row.addProperty("content", data.lastSummary());
            row.addProperty("intent", data.commonIntent() != null ? data.commonIntent() : "");
            row.addProperty("session_id", "");
            row.addProperty("ts", data.lastInteractionTime());
            JsonArray arr = new JsonArray();
            for (Float f : embeddingService.embed(data.lastSummary())) arr.add(f);
            row.add("vector", arr);
            milvusClient.insert(InsertReq.builder()
                    .collectionName(properties.getMemoryCollection())
                    .data(Collections.singletonList(row))
                    .build());
        } catch (Exception e) {
            log.warn("Milvus长期记忆写入失败 [{}]：{}", userId, e.getMessage());
        }
    }

    @Override
    public UserMemoryData get(String userId) {
        return fallback.get(userId);
    }

    @Override
    public void updatePreference(String userId, String key, String value) {
        fallback.updatePreference(userId, key, value);
    }

    /**
     * 记录一次用户交互到长期记忆（语义检索用）
     */
    @Override
    public void recordInteraction(String userId, String sessionId, String content, String intent) {
        fallback.get(userId); // 确保画像存在
        if (!available || milvusClient == null || content == null || content.isBlank()) return;
        try {
            JsonObject row = new JsonObject();
            row.addProperty("user_id", userId);
            row.addProperty("content", content.length() > 1500 ? content.substring(0, 1500) : content);
            row.addProperty("intent", intent != null ? intent : "");
            row.addProperty("session_id", sessionId != null ? sessionId : "");
            row.addProperty("ts", System.currentTimeMillis());
            JsonArray arr = new JsonArray();
            for (Float f : embeddingService.embed(content)) arr.add(f);
            row.add("vector", arr);
            milvusClient.insert(InsertReq.builder()
                    .collectionName(properties.getMemoryCollection())
                    .data(Collections.singletonList(row))
                    .build());
        } catch (Exception e) {
            log.warn("记录交互到Milvus失败 [{}]：{}", userId, e.getMessage());
        }
    }

    /**
     * 按语义相似度检索某用户的历史交互
     */
    @Override
    public List<Map<String, Object>> retrieveRelevant(String userId, String query, int topK) {
        if (!available || milvusClient == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Float> vec = embeddingService.embed(query);
            String filter = String.format("user_id == \"%s\"", userId);
            SearchReq req = SearchReq.builder()
                    .collectionName(properties.getMemoryCollection())
                    .data(Collections.singletonList(new FloatVec(vec)))
                    .topK(topK)
                    .filter(filter)
                    .outputFields(Arrays.asList("content", "intent", "session_id", "ts"))
                    .build();
            SearchResp resp = milvusClient.search(req);
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            List<Map<String, Object>> out = new ArrayList<>();
            if (results != null && !results.isEmpty()) {
                for (SearchResp.SearchResult r : results.get(0)) {
                    Map<String, Object> entity = r.getEntity();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("content", entity.get("content"));
                    item.put("intent", entity.get("intent"));
                    item.put("sessionId", entity.get("session_id"));
                    item.put("timestamp", entity.get("ts"));
                    item.put("score", r.getScore());
                    out.add(item);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Milvus长期记忆检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    public boolean isAvailable() {
        return available;
    }
}
