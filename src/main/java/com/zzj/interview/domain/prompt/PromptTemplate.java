package com.zzj.interview.domain.prompt;

import java.util.Map;

/**
 * Prompt模板端口（Domain层定义）
 *
 * 设计理念：
 * 1. Prompt是AI工程的核心资产，需要版本化管理
 * 2. 支持动态参数注入，实现模板复用
 * 3. Domain层定义接口，Infrastructure层提供实现
 */
public interface PromptTemplate {

    /**
     * 渲染Prompt（填充变量）
     */
    String render(Map<String, Object> variables);

    /**
     * 获取原始模板内容
     */
    String getRawContent();

    /**
     * 获取模板版本
     */
    String getVersion();

    /**
     * 获取模板名称
     */
    String getName();
}
