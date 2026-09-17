package com.zzj.interview.domain.model.conversation;

/**
 * 会话状态
 *
 * 描述一次多轮会话当前所处的交互阶段，是人机交互（澄清/确认）暂停点的状态标记。
 */
public enum ConversationStatus {

    /** 正常进行中，可直接处理新一轮输入 */
    ACTIVE("进行中"),

    /** 等待用户补充缺失信息（问题澄清） */
    WAITING_CLARIFICATION("等待信息补充"),

    /** 等待用户确认敏感操作（如取消订单） */
    WAITING_CONFIRMATION("等待操作确认"),

    /** 会话已结束 */
    CLOSED("已结束");

    private final String description;

    ConversationStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
