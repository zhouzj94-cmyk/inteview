package com.zzj.interview.domain.model.conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话聚合根（多轮对话骨干）
 *
 * 职责：承载跨HTTP请求的会话级状态，使"多轮问答 / 问题补充 / 人机确认"成为可能。
 * 每一轮用户输入对应一个工单(Ticket)，但多个工单归属同一个会话(Conversation)。
 *
 * 核心状态：
 * - status：当前交互阶段（进行中 / 等待补充 / 等待确认）
 * - pendingClarification：上一轮因缺少必填信息而向用户发起的澄清（待本轮补充合并）
 * - pendingAction：上一轮因敏感操作而挂起、等待用户确认的工具调用
 *
 * 不变量（聚合根内部保护）：
 * - 同一时刻至多存在一个挂起的澄清或一个挂起的确认动作
 * - 消费挂起状态后自动回到 ACTIVE
 *
 * 领域纯净：不依赖Spring/数据库/AI框架。
 */
public class Conversation {

    private String conversationId;
    private String customerId;
    private ConversationStatus status;
    private int turnCount;
    private String currentTicketId;
    private PendingClarification pendingClarification;
    private PendingAction pendingAction;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：开启一个新会话
     */
    public static Conversation start(String conversationId, String customerId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        Conversation c = new Conversation();
        c.conversationId = conversationId;
        c.customerId = customerId;
        c.status = ConversationStatus.ACTIVE;
        c.turnCount = 0;
        c.createdAt = LocalDateTime.now();
        c.updatedAt = LocalDateTime.now();
        return c;
    }

    /**
     * 开始新一轮：绑定本轮工单ID并累加轮次
     */
    public void beginTurn(String ticketId) {
        this.currentTicketId = ticketId;
        this.turnCount++;
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== 问题澄清（信息补充） ====================

    /**
     * 发起澄清：记录缺失槽位与澄清问题，转入等待补充状态
     */
    public void requestClarification(String originalContent, String intent,
                                     List<String> missingSlots, String question) {
        this.pendingClarification = new PendingClarification(
                originalContent, intent, missingSlots, question, System.currentTimeMillis());
        this.status = ConversationStatus.WAITING_CLARIFICATION;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasPendingClarification() {
        return pendingClarification != null;
    }

    /**
     * 消费挂起的澄清（用户已补充信息）：取出并清空，状态回到进行中
     */
    public PendingClarification consumeClarification() {
        PendingClarification p = this.pendingClarification;
        this.pendingClarification = null;
        if (this.status == ConversationStatus.WAITING_CLARIFICATION) {
            this.status = ConversationStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
        return p;
    }

    // ==================== 人机确认（敏感操作） ====================

    /**
     * 发起确认：挂起一个敏感工具调用，转入等待确认状态
     */
    public void requestConfirmation(String actionId, String toolName,
                                    Map<String, Object> arguments, String prompt) {
        this.pendingAction = new PendingAction(
                actionId, toolName, arguments, prompt, System.currentTimeMillis());
        this.status = ConversationStatus.WAITING_CONFIRMATION;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasPendingAction() {
        return pendingAction != null;
    }

    /**
     * 消费挂起的确认动作（用户已批准/拒绝）：取出并清空，状态回到进行中
     */
    public PendingAction consumePendingAction() {
        PendingAction p = this.pendingAction;
        this.pendingAction = null;
        if (this.status == ConversationStatus.WAITING_CONFIRMATION) {
            this.status = ConversationStatus.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
        return p;
    }

    /**
     * 清空所有挂起状态（如用户中途改变意图）
     */
    public void clearPending() {
        this.pendingAction = null;
        this.pendingClarification = null;
        this.status = ConversationStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void close() {
        this.status = ConversationStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Getters / Setters ====================

    public String getConversationId() { return conversationId; }
    public String getCustomerId() { return customerId; }
    public ConversationStatus getStatus() { return status; }
    public int getTurnCount() { return turnCount; }
    public String getCurrentTicketId() { return currentTicketId; }
    public PendingClarification getPendingClarification() { return pendingClarification; }
    public PendingAction getPendingAction() { return pendingAction; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public void setStatus(ConversationStatus status) { this.status = status; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public void setCurrentTicketId(String currentTicketId) { this.currentTicketId = currentTicketId; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ==================== 内嵌值对象 ====================

    /**
     * 挂起的澄清请求：保存上一轮的原始诉求与缺失槽位，待用户补充后合并重跑
     */
    public record PendingClarification(
            String originalContent,
            String intent,
            List<String> missingSlots,
            String question,
            long createdAt
    ) {}

    /**
     * 挂起的确认动作：一个等待用户批准后才执行的敏感工具调用
     */
    public record PendingAction(
            String actionId,
            String toolName,
            Map<String, Object> arguments,
            String prompt,
            long createdAt
    ) {}
}
