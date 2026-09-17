package com.zzj.interview.domain.model.ticket;

import java.time.LocalDateTime;

/**
 * 工单聚合根
 *
 * 核心职责：
 * 1. 封装工单的业务规则和状态流转约束
 * 2. 保护自身状态一致性（不依赖外部校验）
 * 3. 不依赖任何框架、HTTP、数据库等基础设施
 *
 * 面试要点：
 * - 聚合根是DDD中最核心的概念，它保证业务不变量
 * - 状态变更方法内嵌校验逻辑，而非在Service中if-else
 */
public class Ticket {

    private String id;
    private String customerId;
    private String content;
    private TicketIntent intent;
    private TicketStatus status;
    private String response;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：创建新工单
     * 使用工厂方法而非公开构造函数，确保创建时的业务规则集中管理
     */
    public static Ticket create(String id, String customerId, String content) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("客户ID不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("工单内容不能为空");
        }

        Ticket ticket = new Ticket();
        ticket.id = id;
        ticket.customerId = customerId;
        ticket.content = content;
        ticket.intent = TicketIntent.UNKNOWN;
        ticket.status = TicketStatus.CREATED;
        ticket.createdAt = LocalDateTime.now();
        ticket.updatedAt = LocalDateTime.now();
        return ticket;
    }

    /**
     * 绑定AI识别的意图
     * 业务规则：只有CREATED状态的工单才能绑定意图
     */
    public void bindIntent(TicketIntent intent) {
        if (this.status != TicketStatus.CREATED) {
            throw new IllegalStateException(
                    "只有已创建状态的工单才能绑定意图，当前状态：" + this.status);
        }
        this.intent = intent;
        this.status = TicketStatus.ANALYZING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为等待用户输入（人机交互暂停点）
     * 用于两类场景：信息澄清（缺少必填槽位）与敏感操作确认（如取消订单）
     * prompt 为向用户展示的澄清问题或确认提示
     */
    public void markWaitingForUser(String prompt) {
        this.status = TicketStatus.WAITING_FOR_USER;
        this.response = prompt;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为已处理
     */
    public void markHandled(String response) {
        this.status = TicketStatus.HANDLED;
        this.response = response;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为待人工处理（AI无法识别或调用失败时的降级策略）
     */
    public void markManualReview() {
        this.status = TicketStatus.MANUAL_REVIEW;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记为处理失败
     */
    public void markFailed(String errorMessage) {
        this.status = TicketStatus.FAILED;
        this.response = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters ===

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getContent() { return content; }
    public TicketIntent getIntent() { return intent; }
    public TicketStatus getStatus() { return status; }
    public String getResponse() { return response; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // === Setters（仅用于从数据库重建对象） ===

    public void setId(String id) { this.id = id; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public void setContent(String content) { this.content = content; }
    public void setIntent(TicketIntent intent) { this.intent = intent; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public void setResponse(String response) { this.response = response; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
