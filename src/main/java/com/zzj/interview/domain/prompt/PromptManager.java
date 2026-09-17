package com.zzj.interview.domain.prompt;

/**
 * Prompt管理器端口（Domain层定义）
 *
 * 职责：
 * 1. 管理所有Prompt模板
 * 2. 支持按名称获取模板
 * 3. 支持模板版本控制
 */
public interface PromptManager {

    /**
     * 获取指定名称的Prompt模板
     */
    PromptTemplate getTemplate(String name);

    /**
     * 获取指定名称和版本的Prompt模板
     */
    PromptTemplate getTemplate(String name, String version);

    /**
     * 注册新的Prompt模板
     */
    void registerTemplate(PromptTemplate template);
}
