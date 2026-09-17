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
 * 取消订单工具（敏感操作，需人工确认）
 *
 * 取消订单会产生不可逆的业务副作用，因此 requiresConfirmation()=true：
 * 编排层在真正执行前会暂停，向用户展示确认提示，待用户批准后才调用本工具。
 *
 * 副作用落库：真正把 `order`.status 更新为 CANCELLED（此前只返回假字符串、不写库，
 * 导致取消后订单状态始终不变）。退款金额取自订单真实金额。
 */
@Component
public class CancelOrderTool implements BusinessTool {

    private final OrderMapper orderMapper;

    public CancelOrderTool(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public String name() {
        return "cancel_order";
    }

    @Override
    public String description() {
        return "取消指定订单（不可逆的敏感操作，执行前必须经过用户确认）。仅在用户明确要求取消订单时调用";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "要取消的订单号"),
                ToolParameter.optionalString("reason", "取消原因")
        );
    }

    /**
     * 敏感操作：必须人工确认后才执行
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));
        String reason = String.valueOf(arguments.getOrDefault("reason", "用户主动取消"));

        OrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ToolResult.failure(name(), "未找到订单，无法取消: " + orderNo);
        }
        // 幂等：已取消的订单不再重复处理，也不重复退款
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(order.getStatus())) {
            return ToolResult.success(name(), String.format("""
                    {
                        "orderNo": "%s",
                        "status": "%s",
                        "message": "订单此前已取消，无需重复操作"
                    }""", order.getOrderNo(), OrderStatus.CANCELLED.label()));
        }
        // 状态守卫：已签收/退款中/已退款不可取消（已签收请走售后退款，退款流程不可逆取消）
        if (OrderStatus.DELIVERED.name().equalsIgnoreCase(order.getStatus())) {
            return ToolResult.failure(name(), "订单已签收，无法取消，如需退货请申请售后退款: " + orderNo);
        }
        if (OrderStatus.REFUNDING.name().equalsIgnoreCase(order.getStatus())) {
            return ToolResult.failure(name(), "订单正在退款中，无法取消: " + orderNo);
        }
        if (OrderStatus.REFUNDED.name().equalsIgnoreCase(order.getStatus())) {
            return ToolResult.failure(name(), "订单已退款，无法取消: " + orderNo);
        }

        // 真正的业务副作用：把订单状态落库为 CANCELLED
        int rows = orderMapper.updateStatusByOrderNo(orderNo, OrderStatus.CANCELLED.name());
        if (rows <= 0) {
            return ToolResult.failure(name(), "取消订单失败，状态未更新: " + orderNo);
        }

        // 后置校验：回查订单确认真的落到了 CANCELLED，而不是只看 UPDATE 影响行数
        OrderEntity verified = orderMapper.selectByOrderNo(orderNo);
        if (verified == null || !OrderStatus.CANCELLED.name().equalsIgnoreCase(verified.getStatus())) {
            return ToolResult.failure(name(),
                    "取消订单校验未通过：回查状态未变为已取消，订单 " + orderNo);
        }

        String cancelInfo = String.format("""
                {
                    "orderNo": "%s",
                    "previousStatus": "%s",
                    "status": "%s",
                    "reason": "%s",
                    "refundAmount": %s,
                    "refundChannel": "原路退回",
                    "estimatedRefundDate": "2026-09-19",
                    "verified": true,
                    "message": "订单已取消，退款将在3个工作日内原路退回"
                }""",
                order.getOrderNo(),
                OrderStatus.labelOf(order.getStatus()),
                OrderStatus.labelOf(verified.getStatus()),
                reason,
                order.getAmount());

        return ToolResult.success(name(), cancelInfo);
    }
}
