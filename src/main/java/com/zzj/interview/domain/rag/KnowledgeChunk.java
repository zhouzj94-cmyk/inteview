package com.zzj.interview.domain.rag;

import java.util.List;
import java.util.Map;

/**
 * 知识片段
 */
public record KnowledgeChunk(
        String id,
        String content,
        String category,
        List<String> keywords,
        Map<String, Object> metadata
) {
    public KnowledgeChunk(String id, String content, String category, List<String> keywords) {
        this(id, content, category, keywords, Map.of());
    }
}
