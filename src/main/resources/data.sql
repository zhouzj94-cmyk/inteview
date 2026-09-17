-- =============================================
-- AI智能客服工单系统 - 模拟数据
-- =============================================

-- 订单数据
INSERT INTO `order` (id, order_no, customer_id, status, amount, created_at) VALUES
('ORD001', '10086', 'C001', 'SHIPPED', 299.00, '2026-09-13 14:30:00'),
('ORD002', '10087', 'C001', 'REFUNDING', 159.00, '2026-09-10 09:15:00'),
('ORD003', '10088', 'C002', 'PAID', 599.00, '2026-09-15 16:45:00'),
('ORD004', '10089', 'C002', 'SHIPPED', 89.00, '2026-09-14 11:20:00'),
('ORD005', '10090', 'C003', 'CREATED', 1299.00, '2026-09-16 08:00:00'),
('ORD006', '10091', 'C003', 'DELIVERED', 459.00, '2026-09-08 13:30:00'),
('ORD007', '10092', 'C004', 'SHIPPED', 199.00, '2026-09-15 10:00:00'),
('ORD008', '10093', 'C004', 'CANCELLED', 79.00, '2026-09-12 15:45:00'),
('ORD009', '10094', 'C005', 'PAID', 899.00, '2026-09-16 09:30:00'),
('ORD010', '10095', 'C005', 'SHIPPED', 349.00, '2026-09-14 14:20:00'),
('ORD011', '10096', 'C001', 'DELIVERED', 229.00, '2026-09-05 10:00:00'),
('ORD012', '10097', 'C002', 'SHIPPED', 179.00, '2026-09-13 16:00:00'),
('ORD013', '10098', 'C003', 'PAID', 699.00, '2026-09-15 11:30:00'),
('ORD014', '10099', 'C004', 'DELIVERED', 129.00, '2026-09-09 09:45:00'),
('ORD015', '10100', 'C005', 'CREATED', 1599.00, '2026-09-16 10:15:00'),
('ORD016', '10101', 'C001', 'CREATED', 199.00, '2026-09-16 12:00:00'),
('ORD017', '10102', 'C001', 'PAID', 399.00, '2026-09-16 13:00:00'),
('ORD018', '10103', 'C001', 'SHIPPED', 259.00, '2026-09-15 09:30:00'),
('ORD019', '10104', 'C001', 'DELIVERED', 189.00, '2026-09-12 16:20:00'),
('ORD020', '10105', 'C001', 'REFUNDED', 99.00, '2026-09-11 10:10:00'),
('ORD021', '10106', 'C001', 'CREATED', 499.00, '2026-09-16 15:45:00'),
('ORD022', '10107', 'C002', 'SHIPPED', 329.00, '2026-09-15 14:00:00'),
('ORD023', '10108', 'C003', 'DELIVERED', 759.00, '2026-09-13 11:25:00');

-- 售后数据（与订单状态联动：10087 处于退款中，对应一条退款中的售后记录，
-- 用于演示 query_after_sale 查询进度、process_refund 退款到账）
INSERT INTO after_sale (id, after_sale_no, order_no, customer_id, type, reason, status, refund_amount, created_at) VALUES
('ASR001', 'AS10087', '10087', 'C001', '退款', '商品质量问题', 'REFUNDING', 159.00, '2026-09-16 14:00:00'),
('ASR002', 'AS10105', '10105', 'C001', '退款', '七天无理由退货', 'REFUNDED', 99.00, '2026-09-11 12:00:00');

-- 工单数据（各种场景）
INSERT INTO ticket (id, customer_id, content, intent, status, response, created_at) VALUES
-- 物流查询类
('TK001', 'C001', '我的订单10086到哪了？什么时候能收到？', 'LOGISTICS', 'HANDLED', 
 '您好！订单10086已通过顺丰速运发出，运单号SF1234567890，目前在上海转运中心，预计明天送达。', '2026-09-15 09:00:00'),
 
('TK002', 'C002', 'order 10088 shipping status', 'LOGISTICS', 'HANDLED',
 'Hello! Order 10088 has been shipped and is currently in transit. Estimated delivery is within 2-3 business days.', '2026-09-15 10:30:00'),

('TK003', 'C003', '查一下10090的物流信息', 'LOGISTICS', 'HANDLED',
 '您好！订单10090已付款待发货，预计24小时内发出。', '2026-09-16 08:30:00'),

-- 售后类
('TK004', 'C001', '订单10087的商品有质量问题，我要退货', 'AFTER_SALE', 'HANDLED',
 '您好！已为您创建退货工单AS10001，请在7天内寄回商品，退款将在收到商品后3-5个工作日内处理。', '2026-09-14 14:00:00'),

('TK005', 'C004', 'I want to refund order 10093', 'AFTER_SALE', 'HANDLED',
 'Hello! A refund request has been created for order 10093. Our customer service will contact you within 24 hours.', '2026-09-15 16:00:00'),

('TK006', 'C002', '10089的商品坏了，要换货', 'AFTER_SALE', 'HANDLED',
 '您好！已为您创建换货工单AS10002，请将商品寄回，我们收到后会尽快为您发出新品。', '2026-09-15 11:00:00'),

-- 订单查询类
('TK007', 'C003', '帮我查一下订单10096的状态', 'ORDER_QUERY', 'HANDLED',
 '您好！订单10096已签收，金额229元，感谢您的购买！', '2026-09-15 15:00:00'),

('TK008', 'C005', 'query order 10095 details', 'ORDER_QUERY', 'HANDLED',
 'Hello! Order 10095 status: PAID, amount: ¥899.00, created at 2026-09-16. Your order will be shipped within 24 hours.', '2026-09-16 09:45:00'),

('TK009', 'C004', '我的订单10099多少钱？', 'ORDER_QUERY', 'HANDLED',
 '您好！订单10099金额129元，已签收。', '2026-09-15 10:30:00'),

-- 未知意图类
('TK010', 'C001', '你们几点下班？', 'UNKNOWN', 'HANDLED',
 '您好！我们的在线客服7x24小时为您服务，电话客服工作时间为9:00-21:00。', '2026-09-15 20:00:00'),

('TK011', 'C005', 'how to become a VIP member?', 'UNKNOWN', 'MANUAL_REVIEW',
 NULL, '2026-09-16 10:30:00'),

-- 最新工单（待处理）
('TK012', 'C003', '订单10098什么时候发货？', 'LOGISTICS', 'CREATED',
 NULL, '2026-09-16 11:00:00'),

('TK013', 'C004', '10092的快递太慢了，催一下', 'LOGISTICS', 'CREATED',
 NULL, '2026-09-16 11:15:00'),

('TK014', 'C002', '订单10097要修改收货地址', 'ORDER_QUERY', 'CREATED',
 NULL, '2026-09-16 11:30:00'),

('TK015', 'C005', '10100能取消吗？', 'AFTER_SALE', 'CREATED',
 NULL, '2026-09-16 11:45:00');

-- AI调用记录（模拟历史数据）
INSERT INTO ticket_ai_record (id, ticket_id, model, prompt, response, intent, latency_ms, success, created_at) VALUES
('AR001', 'TK001', 'qwen-max', '查询订单10086物流', '物流信息：顺丰速运...', 'LOGISTICS', 2350, TRUE, '2026-09-15 09:00:05'),
('AR002', 'TK002', 'qwen-max', 'Query order 10088 shipping', 'Order 10088 has been shipped...', 'LOGISTICS', 1890, TRUE, '2026-09-15 10:30:03'),
('AR003', 'TK004', 'qwen-max', '订单10087退货', '已创建退货工单...', 'AFTER_SALE', 3120, TRUE, '2026-09-14 14:00:08'),
('AR004', 'TK007', 'qwen-max', '查询订单10096状态', '订单已签收...', 'ORDER_QUERY', 1560, TRUE, '2026-09-15 15:00:02'),
('AR005', 'TK010', 'qwen-max', '你们几点下班', '在线客服7x24小时...', 'UNKNOWN', 1230, TRUE, '2026-09-15 20:00:02');
