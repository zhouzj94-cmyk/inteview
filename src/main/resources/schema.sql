-- =============================================
-- AI智能客服工单系统 - 数据库初始化脚本
-- =============================================

-- 工单表
CREATE TABLE IF NOT EXISTS ticket (
    id VARCHAR(64) PRIMARY KEY COMMENT '工单ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '客户ID',
    content TEXT NOT NULL COMMENT '工单内容（用户描述的问题）',
    intent VARCHAR(32) DEFAULT 'UNKNOWN' COMMENT 'AI识别的意图：ORDER_QUERY/LOGISTICS/AFTER_SALE/UNKNOWN',
    status VARCHAR(32) DEFAULT 'CREATED' COMMENT '工单状态：CREATED/ANALYZING/HANDLED/MANUAL_REVIEW/FAILED',
    response TEXT COMMENT 'AI生成的回复内容',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_customer_id (customer_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';

-- AI调用记录表（体现LLMOps思想：AI不是黑盒，需要可追溯）
CREATE TABLE IF NOT EXISTS ticket_ai_record (
    id VARCHAR(64) PRIMARY KEY COMMENT '记录ID',
    ticket_id VARCHAR(64) NOT NULL COMMENT '关联工单ID',
    model VARCHAR(64) COMMENT '使用的AI模型',
    prompt TEXT COMMENT '发送给AI的完整请求',
    response TEXT COMMENT 'AI返回的完整响应',
    intent VARCHAR(32) COMMENT '识别结果',
    latency_ms BIGINT COMMENT '调用延迟（毫秒）',
    success BOOLEAN COMMENT '是否成功',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_ticket_id (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用记录表';

-- 订单表（模拟业务数据）
CREATE TABLE IF NOT EXISTS `order` (
    id VARCHAR(64) PRIMARY KEY COMMENT '订单ID',
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    customer_id VARCHAR(64) NOT NULL COMMENT '客户ID',
    status VARCHAR(32) DEFAULT 'CREATED' COMMENT '订单状态',
    amount DECIMAL(10,2) COMMENT '订单金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_order_no (order_no),
    INDEX idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表（模拟数据）';

-- 售后工单表（记录退款/售后申请，由 create_after_sale 落库、query_after_sale 查询、process_refund 推进状态）
CREATE TABLE IF NOT EXISTS after_sale (
    id VARCHAR(64) PRIMARY KEY COMMENT '售后记录ID',
    after_sale_no VARCHAR(64) NOT NULL UNIQUE COMMENT '售后单号（由订单号派生：AS+orderNo）',
    order_no VARCHAR(64) NOT NULL COMMENT '关联订单号',
    customer_id VARCHAR(64) NOT NULL COMMENT '客户ID',
    type VARCHAR(32) DEFAULT '退款' COMMENT '售后类型：退款/退货/换货/维修',
    reason VARCHAR(255) COMMENT '售后原因',
    status VARCHAR(32) DEFAULT 'REFUNDING' COMMENT '售后状态：REFUNDING（退款中）/REFUNDED（已退款）',
    refund_amount DECIMAL(10,2) COMMENT '退款金额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_no (order_no),
    INDEX idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后工单表';
