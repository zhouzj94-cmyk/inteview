package com.zzj.interview.domain.rag;

import java.util.List;

/**
 * 知识库端口（Domain层定义）
 *
 * RAG（检索增强生成）核心组件：
 * 1. 存储结构化知识（FAQ、产品文档、政策等）
 * 2. 支持语义检索，找到最相关的知识片段
 * 3. 为AI回复提供上下文增强
 */
public interface KnowledgeBase {

    /**
     * 检索相关知识
     * @param query 查询文本
     * @param topK 返回最相关的K条
     */
    List<KnowledgeChunk> retrieve(String query, int topK);

    /**
     * 添加知识片段
     */
    void addChunk(KnowledgeChunk chunk);
}
