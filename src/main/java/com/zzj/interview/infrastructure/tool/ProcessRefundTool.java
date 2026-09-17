package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderStatus;
import com.zzj.interview.infrastructure.persistence.mapper.AfterSaleMapper;
import com.zzj.interview.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 退款到账处理工具（敏感操作，需人工确认）
 *
 * 把"退款中"的订单推进到"已退款"，并同步把 after_sale 售后记录置为已退款。
 * 这是 create_after_sale 之后的后续动作：售后发起（REFUNDING）→ 退款到账（REFUNDED）。
 *
 * 退款到账是资金侧不可逆动作，故 requiresConfirmation()=true。
 * 状态守卫：仅 REFUNDING 可推进；已退款幂等成功；其余状态不可退款到账。
 */
@Component
public class ProcessRefundTool implements BusinessTool {

    private final OrderMapper orderMapper;
    private final AfterSaleMapper afterSaleMapper;

    public ProcessRefundTool(OrderMapper orderMapper, AfterSaleMapper afterSaleMapper) {
        this.orderMapper = orderMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    @Override
    public String name() {
        return "process_refund";
    }

    @Override
    public String description() {
        return "为处于退款中的订单完成退款到账，把订单与售后记录推进到已退款"
                + "（资金侧敏感操作，执行前必须经过用户确认）。仅在用户确认退款到账/完成退款时调用";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "要完成退款到账的订单号")
        );
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));

        OrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ToolResult.failure(name(), "未找到订单，无法处理退款: " + orderNo);
        }

        String status = order.getStatus();
        // 幂等：已退款不重复处理
        if (OrderStatus.REFUNDED.name().equalsIgnoreCase(status)) {
            return ToolResult.success(name(), String.format("""
                    {"orderNo": "%s", "status": "%s", "message": "该订单退款已到账，无需重复处理"}""",
                    order.getOrderNo(), OrderStatus.REFUNDED.label()));
        }
        // 仅"退款中"可推进到账；其余状态说明尚未发起售后或不可退款
        if (!OrderStatus.REFUNDING.name().equalsIgnoreCase(status)) {
            return ToolResult.failure(name(),
                    "订单当前状态为" + OrderStatus.labelOf(status) + "，不在退款中，无法处理退款到账: " + orderNo);
        }

        AfterSaleEntity afterSale = afterSaleMapper.selectByOrderNo(orderNo);

        // 真实副作用：订单状态推进到"已退款"
        int rows = orderMapper.updateStatusByOrderNo(orderNo, OrderStatus.REFUNDED.name());
        if (rows <= 0) {
            return ToolResult.failure(name(), "退款到账失败，订单状态未更新: " + orderNo);
        }
        // 同步售后记录状态（若存在）
        if (afterSale != null) {
            afterSaleMapper.updateStatusByOrderNo(orderNo, OrderStatus.REFUNDED.name());
        }

        // 后置校验：回查订单确认真的落到已退款
        OrderEntity verified = orderMapper.selectByOrderNo(orderNo);
        if (verified == null || !OrderStatus.REFUNDED.name().equalsIgnoreCase(verified.getStatus())) {
            return ToolResult.failure(name(), "退款到账校验未通过：回查状态未变为已退款，订单 " + orderNo);
        }

        String afterSaleNo = afterSale != null ? afterSale.getAfterSaleNo() : ("AS" + orderNo);
        Object refundAmount = afterSale != null && afterSale.getRefundAmount() != null
                ? afterSale.getRefundAmount() : order.getAmount();

        String refundInfo = String.format("""
                {
                    "afterSaleNo": "%s",
                    "orderNo": "%s",
                    "previousStatus": "%s",
                    "status": "%s",
                    "refundAmount": %s,
                    "refundChannel": "原路退回",
                    "verified": true,
                    "message": "退款已到账，款项已原路退回，请注意查收"
                }""",
                afterSaleNo,
                order.getOrderNo(),
                OrderStatus.REFUNDING.label(),
                OrderStatus.REFUNDED.label(),
                refundAmount);

        return ToolResult.success(name(), refundInfo);
    }
}
