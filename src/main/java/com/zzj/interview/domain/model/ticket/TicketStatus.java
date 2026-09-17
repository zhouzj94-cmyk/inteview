package com.zzj.interview.domain.model.ticket;

/**
 * 工单状态枚举
 * 控制工单的生命周期流转，状态变更由Ticket聚合根内部校验
 */
public enum TicketStatus {

    CREATED("已创建"),
    ANALYZING("分析中"),
    WAITING_FOR_USER("等待用户输入"),
    HANDLED("已处理"),
    MANUAL_REVIEW("待人工处理"),
    FAILED("处理失败");

    private final String description;

    TicketStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
