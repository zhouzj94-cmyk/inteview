package com.zzj.interview.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Langfuse监控配置
 * 用于AI调用的可观测性追踪
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai.langfuse")
public class LangfuseProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:3000";
    private String publicKey = "";
    private String secretKey = "";
}
