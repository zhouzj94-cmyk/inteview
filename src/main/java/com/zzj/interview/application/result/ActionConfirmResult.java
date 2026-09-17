package com.zzj.interview.application.result;

/**
 * 人机确认动作的处理结果
 *
 * @param ticketId       关联工单ID
 * @param conversationId 会话ID
 * @param decision       决策：APPROVED / REJECTED
 * @param message        面向用户的自然语言回复
 * @param toolResult     批准执行时工具返回的原始数据（拒绝时为null）
 */
public record ActionConfirmResult(
        String ticketId,
        String conversationId,
        String decision,
        String message,
        String toolResult
) {}
