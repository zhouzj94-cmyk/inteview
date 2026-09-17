package com.zzj.interview.infrastructure.config;

import com.zzj.interview.infrastructure.ai.QwenProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate配置
 * 用于调用Qwen AI HTTP API
 * 配置了连接超时和读取超时，避免AI调用阻塞过久
 *
 * 注：Spring Boot 4移除了RestTemplateBuilder，直接通过Factory配置
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(QwenProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
