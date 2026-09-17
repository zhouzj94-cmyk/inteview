package com.zzj.interview.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus向量数据库配置属性
 * 通过application.yml中的ai.milvus前缀注入
 */
@Component
@ConfigurationProperties(prefix = "ai.milvus")
public class MilvusProperties {

    private String host = "localhost";
    private int port = 19530;
    private boolean enabled = true;
    private String knowledgeCollection = "knowledge_base";
    private String memoryCollection = "user_memory";
    private int embeddingDimension = 1024;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getKnowledgeCollection() { return knowledgeCollection; }
    public void setKnowledgeCollection(String knowledgeCollection) { this.knowledgeCollection = knowledgeCollection; }

    public String getMemoryCollection() { return memoryCollection; }
    public void setMemoryCollection(String memoryCollection) { this.memoryCollection = memoryCollection; }

    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
}
