package com.zzj.interview.infrastructure.config;

import com.zzj.interview.infrastructure.ai.QwenProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j模型配置
 *
 * 用LangChain4j的模型抽象替代裸RestTemplate调用：
 * - ChatLanguageModel：对话/意图识别/Function Calling（OpenAiChatModel指向DashScope兼容端点）
 * - EmbeddingModel：文本向量化（OpenAiEmbeddingModel）
 *
 * 六边形架构体现：
 * - 这里属于Infrastructure层的"驱动适配器"，把LangChain4j技术细节封装成Bean
 * - Domain/Application只依赖端口接口，不感知LangChain4j
 * - 切换模型供应商只需改这里的Builder，不影响领域代码
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    /**
     * 对话模型：通义千问（DashScope OpenAI兼容端点）
     * baseUrl形如 https://dashscope.aliyuncs.com/compatible-mode/v1
     * OpenAiChatModel会自动拼接 /chat/completions
     */
    @Bean
    public ChatLanguageModel chatLanguageModel(QwenProperties properties) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .temperature(0.3)
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .maxRetries(properties.getMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
        log.info("LangChain4j ChatLanguageModel初始化: model={}, baseUrl={}",
                properties.getModel(), properties.getBaseUrl());
        return model;
    }

    /**
     * 向量化模型：DashScope text-embedding-v3
     * dimensions对应Milvus集合的向量维度
     */
    @Bean
    public EmbeddingModel embeddingModel(
            QwenProperties qwenProperties,
            @Value("${ai.embedding.model:text-embedding-v3}") String embeddingModelName,
            @Value("${ai.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String embeddingBaseUrl,
            @Value("${ai.milvus.embedding-dimension:1024}") int dimension) {
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(qwenProperties.getApiKey())
                .modelName(embeddingModelName)
                .dimensions(dimension)
                .timeout(Duration.ofMillis(qwenProperties.getReadTimeoutMs()))
                .maxRetries(qwenProperties.getMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
        log.info("LangChain4j EmbeddingModel初始化: model={}, dimension={}", embeddingModelName, dimension);
        return model;
    }
}
