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
 * 订单支付工具（敏感操作，需人工确认）
 *
 * 把"待付款"订单推进到"已支付"。支付有资金副作用，故 requiresConfirmation()=true，
 * 编排层执行前会暂停并向用户确认。
 *
 * 状态守卫：仅 CREATED（待付款）可支付；已支付/已发货/已签收幂等成功；
 * 已取消/退款中/已退款不可支付。真实副作用落库后回查校验。
 */
@Component
public class PayOrderTool implements BusinessTool {

    private final OrderMapper orderMapper;

    public PayOrderTool(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public String name() {
        return "pay_order";
    }

    @Override
    public String description() {
        return "为指定的待付款订单完成支付（资金侧敏感操作，执行前必须经过用户确认）。"
                + "仅在用户明确表示要支付/付款某笔订单时调用";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "要支付的订单号"),
                ToolParameter.optionalString("payMethod", "支付方式，如支付宝/微信/银行卡")
        );
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));
        String payMethod = String.valueOf(arguments.getOrDefault("payMethod", "在线支付"));

        OrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ToolResult.failure(name(), "未找到订单，无法支付: " + orderNo);
        }

        String status = order.getStatus();
        // 幂等：已支付及之后的正向状态，视为支付已完成
        if (OrderStatus.PAID.name().equalsIgnoreCase(status)
                || OrderStatus.SHIPPED.name().equalsIgnoreCase(status)
                || OrderStatus.DELIVERED.name().equalsIgnoreCase(status)) {
            return ToolResult.success(name(), String.format("""
                    {"orderNo": "%s", "status": "%s", "message": "该订单已支付，无需重复付款"}""",
                    order.getOrderNo(), OrderStatus.labelOf(status)));
        }
        // 不可支付的状态
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(status)) {
            return ToolResult.failure(name(), "订单已取消，无法支付: " + orderNo);
        }
        if (OrderStatus.REFUNDING.name().equalsIgnoreCase(status)
                || OrderStatus.REFUNDED.name().equalsIgnoreCase(status)) {
            return ToolResult.failure(name(), "订单处于退款流程中，无法支付: " + orderNo);
        }

        // 真实副作用：把订单状态推进到"已支付"
        int rows = orderMapper.updateStatusByOrderNo(orderNo, OrderStatus.PAID.name());
        if (rows <= 0) {
            return ToolResult.failure(name(), "支付失败，状态未更新: " + orderNo);
        }

        // 后置校验：回查确认真的进入已支付
        OrderEntity verified = orderMapper.selectByOrderNo(orderNo);
        if (verified == null || !OrderStatus.PAID.name().equalsIgnoreCase(verified.getStatus())) {
            return ToolResult.failure(name(), "支付校验未通过：回查状态未变为已支付，订单 " + orderNo);
        }

        String payInfo = String.format("""
                {
                    "orderNo": "%s",
                    "previousStatus": "%s",
                    "status": "%s",
                    "amount": %s,
                    "payMethod": "%s",
                    "verified": true,
                    "message": "支付成功，订单已进入待发货，我们将尽快为您安排发货"
                }""",
                order.getOrderNo(),
                OrderStatus.labelOf(order.getStatus()),
                OrderStatus.PAID.label(),
                order.getAmount(),
                payMethod);

        return ToolResult.success(name(), payInfo);
    }
}
