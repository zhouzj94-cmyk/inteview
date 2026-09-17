package com.zzj.interview.infrastructure.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chroma本地向量库配置
 *
 * Chroma是轻量单容器向量数据库，通过HTTP REST API访问。
 * 用于长期记忆的语义向量存储与检索，替代笨重的Milvus。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.chroma")
public class ChromaProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private String collection = "user_memory";
    private int embeddingDimension = 1024;
    /** true=存储前先调用大模型压缩为要点摘要 */
    private boolean compress = true;
}
