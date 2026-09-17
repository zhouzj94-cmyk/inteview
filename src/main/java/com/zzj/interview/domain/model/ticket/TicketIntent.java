package com.zzj.interview.domain.model.ticket;

/**
 * 工单意图枚举
 * AI识别后，工单会被归类到以下意图之一
 */
public enum TicketIntent {

    ORDER_QUERY("订单查询"),
    LOGISTICS("物流问题"),
    AFTER_SALE("售后服务"),
    CANCEL_ORDER("取消订单"),
    CLARIFY("信息澄清"),
    UNKNOWN("无法识别");

    private final String description;

    TicketIntent(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
