package com.zzj.interview.infrastructure.persistence.entity;

/**
 * 订单状态：状态码（与 `order`.status 列一致）与中文展示label的映射
 *
 * 数据库存英文码（如 SHIPPED），面向用户/AI 展示中文（如"已发货"）。
 */
public enum OrderStatus {

    CREATED("待付款"),
    PAID("已支付"),
    SHIPPED("已发货"),
    DELIVERED("已签收"),
    CANCELLED("已取消"),
    REFUNDING("退款中"),
    REFUNDED("已退款");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 状态码转中文label；未知码原样返回，避免丢信息
     */
    public static String labelOf(String code) {
        if (code == null) {
            return "未知";
        }
        for (OrderStatus s : values()) {
            if (s.name().equalsIgnoreCase(code)) {
                return s.label;
            }
        }
        return code;
    }
}
