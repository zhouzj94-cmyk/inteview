package com.zzj.interview.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步和重试配置
 *
 * @EnableAsync：启用Spring异步方法支持
 * @EnableRetry：启用Spring Retry（用于AI调用自动重试）
 *
 * 面试要点：
 * "AI调用使用独立线程池异步执行，不阻塞主线程。
 *  配合Spring Retry实现指数退避重试，提高AI调用成功率。"
 */
@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

    /**
     * 工单处理专用线程池
     * 与Tomcat线程池隔离，避免AI长时间响应耗尽Web线程
     */
    @Bean("ticketExecutor")
    public Executor ticketExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ticket-processor-");
        executor.initialize();
        return executor;
    }
}
