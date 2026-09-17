package com.zzj.interview;

import com.zzj.interview.infrastructure.ai.LangfuseProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI智能客服工单系统 - 启动类
 *
 * 架构：轻量DDD + 六边形架构
 * 分层：Interfaces → Application → Domain ← Infrastructure
 *
 * 核心原则：Domain不知道Spring、MyBatis、Qwen、HTTP的存在
 */
@SpringBootApplication
@MapperScan("com.zzj.interview.infrastructure.persistence.mapper")
@EnableConfigurationProperties(LangfuseProperties.class)
@EnableAsync
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
