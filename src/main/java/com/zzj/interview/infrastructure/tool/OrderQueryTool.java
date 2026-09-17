package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderStatus;
import com.zzj.interview.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 订单查询工具
 *
 * 当AI识别到用户想查询订单时，通过Function Calling调用此工具。
 * 直接读取真实 `order` 表（此前为硬编码假数据，导致取消后状态查询不到变化）。
 */
@Component
public class OrderQueryTool implements BusinessTool {

    private final OrderMapper orderMapper;

    public OrderQueryTool(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public String name() {
        return "query_order";
    }

    @Override
    public String description() {
        return "根据订单号查询订单信息，包括订单状态、金额、下单时间等";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "订单号，如10086")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));

        OrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ToolResult.failure(name(), "未找到订单: " + orderNo);
        }

        String orderInfo = String.format("""
                {
                    "orderNo": "%s",
                    "status": "%s",
                    "statusCode": "%s",
                    "amount": %s,
                    "customerId": "%s",
                    "createdAt": "%s"
                }""",
                order.getOrderNo(),
                OrderStatus.labelOf(order.getStatus()),
                order.getStatus(),
                order.getAmount(),
                order.getCustomerId(),
                order.getCreatedAt());

        return ToolResult.success(name(), orderInfo);
    }
}
