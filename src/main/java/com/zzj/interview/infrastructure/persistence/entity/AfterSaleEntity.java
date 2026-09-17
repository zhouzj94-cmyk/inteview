package com.zzj.interview.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后持久化实体（对应 `after_sale` 表）
 *
 * 由 create_after_sale 工具落库、query_after_sale 工具读取、process_refund 工具推进状态。
 * 字段命名遵循驼峰，依赖 MyBatis 的下划线转驼峰自动映射（refund_amount → refundAmount）。
 */
public class AfterSaleEntity {

    private String id;
    private String afterSaleNo;
    private String orderNo;
    private String customerId;
    private String type;
    private String reason;
    private String status;
    private BigDecimal refundAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAfterSaleNo() { return afterSaleNo; }
    public void setAfterSaleNo(String afterSaleNo) { this.afterSaleNo = afterSaleNo; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
