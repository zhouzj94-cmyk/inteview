package com.zzj.interview.domain.model.ticket;

import java.time.LocalDateTime;

/**
 * AI调用记录实体
 *
 * 记录每次AI调用的详细信息，用于：
 * 1. 问题排查：AI不是黑盒，需要可追溯
 * 2. 成本分析：记录token消耗（后续扩展）
 * 3. 效果评估：统计识别准确率和响应延迟
 *
 * 这体现了LLMOps的基本思想
 */
public class TicketAiRecord {

    private String id;
    private String ticketId;
    private String model;
    private String prompt;
    private String response;
    private String intent;
    private Long latencyMs;
    private Boolean success;
    private LocalDateTime createdAt;

    public static TicketAiRecord create(String id, String ticketId, String model,
                                         String prompt, String response,
                                         String intent, Long latencyMs, Boolean success) {
        TicketAiRecord record = new TicketAiRecord();
        record.id = id;
        record.ticketId = ticketId;
        record.model = model;
        record.prompt = prompt;
        record.response = response;
        record.intent = intent;
        record.latencyMs = latencyMs;
        record.success = success;
        record.createdAt = LocalDateTime.now();
        return record;
    }

    public String getId() { return id; }
    public String getTicketId() { return ticketId; }
    public String getModel() { return model; }
    public String getPrompt() { return prompt; }
    public String getResponse() { return response; }
    public String getIntent() { return intent; }
    public Long getLatencyMs() { return latencyMs; }
    public Boolean getSuccess() { return success; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
