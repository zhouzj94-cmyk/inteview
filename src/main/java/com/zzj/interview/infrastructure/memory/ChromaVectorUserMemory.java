package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.memory.UserMemory;
import com.zzj.interview.infrastructure.ai.ChromaProperties;
import com.zzj.interview.infrastructure.ai.ChromaVectorStore;
import com.zzj.interview.infrastructure.ai.EmbeddingService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基于Chroma的向量长期记忆实现
 *
 * 链路：一次交互 → 调大模型压缩为要点摘要 → embedding向量化 → upsert进Chroma(cosine)
 *      检索时：查询向量化 → Chroma按 user_id 过滤做语义Top-K
 *
 * 职责边界：
 * - 用户“画像”(profile：偏好/交互次数/最近摘要)仍走内存 {@link InMemoryUserMemory}，读取快；
 * - 用户“历史交互”的语义向量存Chroma，支持跨会话语义检索；
 * - Chroma不可用时自动降级为纯内存画像（store.upsert/query返回空/false，不抛异常）。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "ai.chroma", name = "enabled", havingValue = "true")
public class ChromaVectorUserMemory implements UserMemory {

    private static final Logger log = LoggerFactory.getLogger(ChromaVectorUserMemory.class);
    private static final int MAX_RAW_LEN = 1500;

    private final InMemoryUserMemory fallback;
    private final EmbeddingService embeddingService;
    private final ChromaVectorStore store;
    private final ChromaProperties properties;
    private final ChatLanguageModel chatModel;

    @Autowired
    public ChromaVectorUserMemory(InMemoryUserMemory fallback,
                                   EmbeddingService embeddingService,
                                   @Autowired(required = false) ChromaVectorStore store,
                                   ChromaProperties properties,
                                   @Autowired(required = false) ChatLanguageModel chatModel) {
        this.fallback = fallback;
        this.embeddingService = embeddingService;
        this.store = store;
        this.properties = properties;
        this.chatModel = chatModel;
    }

    @PostConstruct
    public void init() {
        boolean ok = store != null && store.isAvailable();
        log.info("Chroma长期记忆初始化：available={} compress={}", ok, properties.isCompress());
    }

    private boolean storeReady() {
        return store != null && store.isAvailable();
    }

    @Override
    public void save(String userId, UserMemoryData data) {
        fallback.save(userId, data);
        // 画像更新时，把最近摘要作为一条长期记忆写入Chroma（若有）
        if (!storeReady() || data.lastSummary() == null || data.lastSummary().isBlank()) {
            return;
        }
        try {
            List<Float> vec = embeddingService.embed(data.lastSummary());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("user_id", userId);
            meta.put("intent", data.commonIntent() != null ? data.commonIntent() : "");
            meta.put("session_id", "");
            meta.put("ts", data.lastInteractionTime());
            meta.put("kind", "summary");
            store.upsert(UUID.randomUUID().toString(), vec, data.lastSummary(), meta);
        } catch (Exception e) {
            log.warn("Chroma写入画像摘要失败 [{}]：{}", userId, e.getMessage());
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

    @Override
    public void recordInteraction(String userId, String sessionId, String content, String intent) {
        fallback.get(userId); // 确保画像存在
        if (!storeReady() || content == null || content.isBlank()) {
            return;
        }
        try {
            String stored = properties.isCompress() ? compress(content) : truncate(content);
            List<Float> vec = embeddingService.embed(stored);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("user_id", userId);
            meta.put("intent", intent != null ? intent : "");
            meta.put("session_id", sessionId != null ? sessionId : "");
            meta.put("ts", System.currentTimeMillis());
            meta.put("kind", "interaction");
            boolean ok = store.upsert(UUID.randomUUID().toString(), vec, stored, meta);
            if (ok) {
                log.debug("长期记忆已写入Chroma [user={} intent={}]：{}", userId, intent, stored);
            }
        } catch (Exception e) {
            log.warn("记录交互到Chroma失败 [{}]：{}", userId, e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> retrieveRelevant(String userId, String query, int topK) {
        if (!storeReady() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Float> vec = embeddingService.embed(query);
            List<Map<String, Object>> hits = store.query(vec, topK, Map.of("user_id", userId));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", h.get("document"));
                Object meta = h.get("metadata");
                if (meta instanceof Map<?, ?> m) {
                    item.put("intent", m.get("intent"));
                    item.put("sessionId", m.get("session_id"));
                    item.put("timestamp", m.get("ts"));
                }
                Object dist = h.get("distance");
                double d = dist instanceof Number ? ((Number) dist).doubleValue() : 0.0;
                item.put("score", 1.0 - d); // cosine距离转相似度
                out.add(item);
            }
            return out;
        } catch (Exception e) {
            log.warn("Chroma长期记忆检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 调用大模型把一次交互压缩成不超过60字的要点；失败则退化为截断原文
     */
    private String compress(String content) {
        if (chatModel == null) {
            return truncate(content);
        }
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from("你是对话压缩器。把用户的一次客服交互压缩成不超过60字的中文要点，" +
                            "只输出要点本身，不要解释、不要前缀。"),
                    UserMessage.from(truncate(content))
            );
            Response<AiMessage> resp = chatModel.generate(messages);
            String text = resp.content() != null ? resp.content().text() : null;
            return (text != null && !text.isBlank()) ? text.trim() : truncate(content);
        } catch (Exception e) {
            log.warn("记忆压缩调用失败，退化为截断：{}", e.getMessage());
            return truncate(content);
        }
    }

    private static String truncate(String s) {
        return s.length() > MAX_RAW_LEN ? s.substring(0, MAX_RAW_LEN) : s;
    }

    public boolean isAvailable() {
        return storeReady();
    }
}
