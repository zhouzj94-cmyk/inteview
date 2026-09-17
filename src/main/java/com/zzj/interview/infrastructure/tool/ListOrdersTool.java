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
 * 订单列表查询工具
 *
 * 支撑"我有哪些订单 / 我的订单列表"这类**无需订单号**的查询：直接按客户ID列出其全部订单。
 * 只读、无副作用，requiresConfirmation=false。
 *
 * 安全要点：customerId 是**服务端上下文身份**（由控制器强制为当前登录客户），
 * 由编排图在执行前权威注入并覆盖，绝不采用模型自行填写的值，防止越权查询他人订单。
 * 因此这里把 customerId 声明为 optional，避免 Function Calling 的 JSON Schema 逼迫模型臆造。
 */
@Component
public class ListOrdersTool implements BusinessTool {

    private final OrderMapper orderMapper;

    public ListOrdersTool(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public String name() {
        return "list_orders";
    }

    @Override
    public String description() {
        return "查询当前客户的全部订单列表（无需订单号）。当用户问'我有哪些订单/我的订单'时使用。"
                + "客户身份由系统自动填充，通常无需提供 customerId。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.optionalString("customerId", "客户ID（系统自动填充，通常无需提供）")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object cid = arguments != null ? arguments.get("customerId") : null;
        String customerId = cid != null ? String.valueOf(cid) : "";
        if (customerId.isBlank() || "null".equalsIgnoreCase(customerId)) {
            return ToolResult.failure(name(), "缺少客户身份，无法查询订单列表");
        }

        List<OrderEntity> orders = orderMapper.selectByCustomerId(customerId);
        if (orders == null || orders.isEmpty()) {
            return ToolResult.success(name(), String.format("""
                    {"customerId": "%s", "total": 0, "orders": [], "message": "该客户暂无订单"}""",
                    customerId));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"customerId\": \"").append(customerId)
                .append("\", \"total\": ").append(orders.size())
                .append(", \"orders\": [");
        for (int i = 0; i < orders.size(); i++) {
            OrderEntity o = orders.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("""
                    {"orderNo": "%s", "status": "%s", "statusCode": "%s", "amount": %s, "createdAt": "%s"}""",
                    o.getOrderNo(),
                    OrderStatus.labelOf(o.getStatus()),
                    o.getStatus(),
                    o.getAmount(),
                    o.getCreatedAt()));
        }
        sb.append("]}");
        return ToolResult.success(name(), sb.toString());
    }
}
