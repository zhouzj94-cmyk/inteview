package com.zzj.interview.application.command;

/**
 * 创建工单命令
 * 使用Java Record保证不可变性，Command只负责传递参数，不包含业务逻辑
 *
 * conversationId：会话ID（多轮对话）。为空时由应用层生成新会话；
 *                 非空时表示在既有会话中追加新一轮对话。
 */
public record CreateTicketCommand(
        String customerId,
        String content,
        String conversationId
) {
    /**
     * 向后兼容构造器：无会话ID（单轮）
     */
    public CreateTicketCommand(String customerId, String content) {
        this(customerId, content, null);
    }
}
