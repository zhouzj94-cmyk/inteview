package com.zzj.interview.infrastructure.rag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zzj.interview.domain.rag.KnowledgeBase;
import com.zzj.interview.domain.rag.KnowledgeChunk;
import com.zzj.interview.infrastructure.ai.EmbeddingService;
import com.zzj.interview.infrastructure.ai.MilvusProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
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

/**
 * 基于Milvus的向量知识库实现
 *
 * 设计思路：
 * 1. 使用Milvus存储知识片段的语义向量
 * 2. 通过EmbeddingService将文本转为向量
 * 3. 使用COSINE相似度检索最相关知识
 * 4. 启动时自动从InMemoryKnowledgeBase迁移种子数据
 *
 * 降级策略：
 * - 当Milvus不可用时，自动降级为内存检索
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "ai.milvus", name = "enabled", havingValue = "true")
public class MilvusVectorKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorKnowledgeBase.class);
    private static final Gson GSON = new Gson();

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusProperties properties;
    private final InMemoryKnowledgeBase fallback;

    private volatile boolean available = false;

    @Autowired
    public MilvusVectorKnowledgeBase(@Autowired(required = false) MilvusClientV2 milvusClient,
                                      EmbeddingService embeddingService,
                                      MilvusProperties properties,
                                      InMemoryKnowledgeBase fallback) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.fallback = fallback;
    }

    @PostConstruct
    public void init() {
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，使用内存知识库降级");
            return;
        }
        try {
            ensureCollection();
            seedFromFallback();
            available = true;
            log.info("Milvus向量知识库初始化成功，集合：{}", properties.getKnowledgeCollection());
        } catch (Exception e) {
            log.warn("Milvus知识库初始化失败，降级为内存：{}", e.getMessage());
            available = false;
        }
    }

    private void ensureCollection() {
        String coll = properties.getKnowledgeCollection();
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(coll).build());
        if (exists) return;

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id").dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content").dataType(DataType.VarChar).maxLength(4096).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("category").dataType(DataType.VarChar).maxLength(64).build());
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
                .description("客服知识库-语义向量")
                .collectionSchema(schema)
                .indexParams(indexes)
                .build();
        milvusClient.createCollection(req);
        log.info("创建Milvus知识库集合：{}", coll);
    }

    private void seedFromFallback() {
        // 触发InMemoryKnowledgeBase加载种子数据
        List<KnowledgeChunk> seeded = fallback.retrieve("退换货 物流 支付 售后 订单 发票 会员", 20);
        if (seeded.isEmpty()) return;
        int count = 0;
        for (KnowledgeChunk chunk : seeded) {
            try {
                addChunk(chunk);
                count++;
            } catch (Exception e) {
                log.warn("种子数据迁移失败 [{}]：{}", chunk.id(), e.getMessage());
            }
        }
        log.info("从内存知识库迁移{}条种子数据到Milvus", count);
    }

    @Override
    public List<KnowledgeChunk> retrieve(String query, int topK) {
        if (!available || milvusClient == null || query == null || query.isBlank()) {
            return fallback.retrieve(query, topK);
        }
        try {
            List<Float> vec = embeddingService.embed(query);
            SearchReq req = SearchReq.builder()
                    .collectionName(properties.getKnowledgeCollection())
                    .data(Collections.singletonList(new FloatVec(vec)))
                    .topK(topK)
                    .outputFields(Arrays.asList("chunk_id", "content", "category"))
                    .build();
            SearchResp resp = milvusClient.search(req);
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            List<KnowledgeChunk> chunks = new ArrayList<>();
            if (results != null && !results.isEmpty()) {
                for (SearchResp.SearchResult r : results.get(0)) {
                    Map<String, Object> entity = r.getEntity();
                    String chunkId = String.valueOf(entity.get("chunk_id"));
                    String content = String.valueOf(entity.get("content"));
                    String category = String.valueOf(entity.get("category"));
                    chunks.add(new KnowledgeChunk(chunkId, content, category, List.of(),
                            Map.of("score", r.getScore())));
                }
            }
            return chunks.isEmpty() ? fallback.retrieve(query, topK) : chunks;
        } catch (Exception e) {
            log.warn("Milvus检索失败，降级为内存：{}", e.getMessage());
            return fallback.retrieve(query, topK);
        }
    }

    @Override
    public void addChunk(KnowledgeChunk chunk) {
        if (!available || milvusClient == null) {
            fallback.addChunk(chunk);
            return;
        }
        try {
            List<Float> vec = embeddingService.embed(chunk.content());
            JsonObject row = new JsonObject();
            row.addProperty("chunk_id", chunk.id());
            row.addProperty("content", chunk.content());
            row.addProperty("category", chunk.category() != null ? chunk.category() : "");
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Float f : vec) arr.add(f);
            row.add("vector", arr);
            milvusClient.insert(InsertReq.builder()
                    .collectionName(properties.getKnowledgeCollection())
                    .data(Collections.singletonList(row))
                    .build());
        } catch (Exception e) {
            log.warn("Milvus插入失败 [{}]：{}", chunk.id(), e.getMessage());
            fallback.addChunk(chunk);
        }
    }

    public boolean isAvailable() {
        return available;
    }
}
