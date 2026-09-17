package com.zzj.interview.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Langfuse客户端 - AI调用追踪
 *
 * 功能：
 * 1. 记录每次AI调用的输入/输出
 * 2. 追踪延迟、token使用、成本
 * 3. 支持Trace/Span(Observation)两层结构
 *
 * 架构说明：
 * - 这是Infrastructure层组件，Domain层不知道Langfuse的存在
 * - 通过异步方式发送追踪数据，不影响主业务流程
 *
 * API说明：
 * Langfuse v2/v3 的所有写入都走批量摄取接口 POST /api/public/ingestion，
 * 每个事件包含 {id, type, timestamp, body}；type 取值：
 * trace-create / span-create / generation-create / event-create。
 * 鉴权使用 Basic Auth：username=publicKey，password=secretKey。
 */
@Component
public class LangfuseClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseClient.class);

    private final RestTemplate restTemplate;
    private final LangfuseProperties properties;

    public LangfuseClient(RestTemplate restTemplate, LangfuseProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 创建Trace（追踪会话），返回traceId供后续Observation关联
     */
    public String createTrace(String name, String sessionId, Map<String, Object> metadata) {
        String traceId = "trace-" + UUID.randomUUID();
        if (!properties.isEnabled()) {
            return traceId;
        }

        try {
            Instant now = Instant.now();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", traceId);
            body.put("name", name);
            body.put("timestamp", now.toString());
            body.put("sessionId", sessionId);
            body.put("metadata", metadata != null ? metadata : Map.of());

            ingest(List.of(event("trace-create", now, body)));
            log.debug("Langfuse Trace创建: {}", traceId);
        } catch (Exception e) {
            log.warn("Langfuse Trace创建失败: {}", e.getMessage());
        }
        return traceId;
    }

    /**
     * 创建Span（记录一次AI调用，含模型与token用量）
     */
    public void createSpan(String traceId, String name, String model,
                           String input, String output,
                           long latencyMs, int promptTokens, int completionTokens) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            Instant end = Instant.now();
            Instant start = end.minusMillis(latencyMs);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "span-" + UUID.randomUUID());
            body.put("traceId", traceId);
            body.put("name", name);
            body.put("startTime", start.toString());
            body.put("endTime", end.toString());
            body.put("model", model);
            body.put("input", input);
            body.put("output", output);
            body.put("usage", Map.of(
                    "input", promptTokens,
                    "output", completionTokens,
                    "unit", "TOKENS"
            ));

            ingest(List.of(event("generation-create", end, body)));
            log.debug("Langfuse Span记录: trace={}, model={}, latency={}ms",
                    traceId, model, latencyMs);
        } catch (Exception e) {
            log.warn("Langfuse Span记录失败: {}", e.getMessage());
        }
    }

    /**
     * 记录事件（简单日志型追踪）
     */
    public void createEvent(String traceId, String name, Map<String, Object> metadata) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            Instant now = Instant.now();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "event-" + UUID.randomUUID());
            body.put("traceId", traceId);
            body.put("name", name);
            body.put("startTime", now.toString());
            body.put("metadata", metadata != null ? metadata : Map.of());

            ingest(List.of(event("event-create", now, body)));
        } catch (Exception e) {
            log.warn("Langfuse Event记录失败: {}", e.getMessage());
        }
    }

    /** 构建单个摄取事件包装 */
    private Map<String, Object> event(String type, Instant timestamp, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("timestamp", timestamp.toString());
        event.put("body", body);
        return event;
    }

    /** 调用批量摄取接口 POST /api/public/ingestion */
    private void ingest(List<Map<String, Object>> batch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Basic Auth：publicKey 作为用户名，secretKey 作为密码
        headers.setBasicAuth(properties.getPublicKey(), properties.getSecretKey());

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(Map.of("batch", batch), headers);

        restTemplate.exchange(
                properties.getBaseUrl() + "/api/public/ingestion",
                HttpMethod.POST,
                entity,
                String.class
        );
    }
}
