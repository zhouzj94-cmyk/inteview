package com.zzj.interview.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单持久化实体（对应 `order` 表，模拟外部业务系统的订单数据）
 *
 * 工具层通过 OrderMapper 读写它，使"查询订单/取消订单"落到真实数据，
 * 而非此前硬编码的假字符串。字段命名遵循驼峰，依赖 MyBatis 的下划线转驼峰自动映射。
 */
public class OrderEntity {

    private String id;
    private String orderNo;
    private String customerId;
    private String status;
    private BigDecimal amount;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
