package com.zzj.interview.infrastructure.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文本向量化服务
 *
 * 用LangChain4j的EmbeddingModel抽象替代裸RestTemplate调用：
 * - 真实模式：EmbeddingModel.embed(text)（OpenAiEmbeddingModel指向DashScope text-embedding-v3）
 * - 降级模式：当mock启用或API不可用时，使用确定性哈希伪向量（基于n-gram），保证演示稳定
 *
 * 架构说明：
 * - EmbeddingModel Bean由LangChain4jConfig提供（Infrastructure驱动适配器）
 * - 本服务被RAG知识库和长期记忆使用，向量化细节对它们透明
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final QwenProperties qwenProperties;
    private final MilvusProperties milvusProperties;

    @Value("${ai.embedding.mock-enabled:false}")
    private boolean mockEnabled;

    public EmbeddingService(EmbeddingModel embeddingModel,
                            QwenProperties qwenProperties,
                            MilvusProperties milvusProperties) {
        this.embeddingModel = embeddingModel;
        this.qwenProperties = qwenProperties;
        this.milvusProperties = milvusProperties;
    }

    /**
     * 将文本转为向量（维度由配置决定）
     */
    public List<Float> embed(String text) {
        if (mockEnabled || qwenProperties.isMockEnabled()) {
            return mockEmbed(text, milvusProperties.getEmbeddingDimension());
        }
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            List<Float> vector = response.content().vectorAsList();
            if (vector != null && !vector.isEmpty()) {
                return vector;
            }
            throw new RuntimeException("embedding响应为空");
        } catch (Exception e) {
            log.warn("LangChain4j embedding调用失败，降级为伪向量：{}", e.getMessage());
            return mockEmbed(text, milvusProperties.getEmbeddingDimension());
        }
    }

    /**
     * 确定性伪向量：基于字符n-gram哈希归一化，同文本同向量，近义文本距离较近
     */
    public static List<Float> mockEmbed(String text, int dimension) {
        float[] vec = new float[dimension];
        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        // 2-gram + 3-gram 哈希
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i <= normalized.length() - n; i++) {
                String gram = normalized.substring(i, i + n);
                int idx = positiveHash(gram.hashCode()) % dimension;
                vec[idx] += 1.0f;
            }
        }
        // L2归一化
        float norm = 0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
        List<Float> result = new java.util.ArrayList<>(dimension);
        for (float v : vec) result.add(v);
        return result;
    }

    private static int positiveHash(int h) {
        return h == Integer.MIN_VALUE ? 0 : Math.abs(h);
    }
}
