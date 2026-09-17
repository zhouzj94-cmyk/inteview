package com.zzj.interview.infrastructure.ai;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus向量数据库客户端配置
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.milvus", name = "enabled", havingValue = "true")
public class MilvusClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusClientConfig.class);

    private volatile MilvusClientV2 client;

    @Bean
    public MilvusClientV2 milvusClient(MilvusProperties properties) {
        if (!properties.isEnabled()) {
            log.info("Milvus已禁用，跳过客户端初始化");
            return null;
        }
        try {
            String uri = String.format("http://%s:%d", properties.getHost(), properties.getPort());
            client = new MilvusClientV2(ConnectConfig.builder()
                    .uri(uri)
                    .connectTimeoutMs(5000)
                    .keepAliveTimeMs(30000)
                    .build());
            log.info("Milvus客户端初始化成功：{}", uri);
            return client;
        } catch (Exception e) {
            log.warn("Milvus客户端初始化失败，将使用降级方案：{}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭Milvus客户端失败：{}", e.getMessage());
            }
        }
    }
}
