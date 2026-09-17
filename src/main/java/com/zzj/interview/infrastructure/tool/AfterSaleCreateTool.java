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
import java.util.UUID;

/**
 * 售后工单创建工具（敏感操作，需人工确认）
 *
 * 此前是空壳：退款金额硬编码 299、售后单号用时间戳凑、不读订单也不落库。
 * 现在做实：
 * - 读真实订单，退款金额取订单真实 amount
 * - 状态守卫：仅已支付/已发货/已签收的订单可申请退款；待付款无款可退、已取消走取消退款、
 *   已退款/退款中幂等返回
 * - 真实副作用：把订单状态推进到 REFUNDING（退款中）并落库，再回查校验
 * - 退款会产生资金侧不可逆影响，故 requiresConfirmation()=true，执行前经用户确认
 *
 * 说明：售后单号由订单号派生（AS+orderNo），稳定可追溯，不再用时间戳凑数。
 * 退款完成（REFUNDING→REFUNDED）由后续退款到账流程推进，本工具只负责发起。
 */
@Component
public class AfterSaleCreateTool implements BusinessTool {

    private final OrderMapper orderMapper;
    private final AfterSaleMapper afterSaleMapper;

    public AfterSaleCreateTool(OrderMapper orderMapper, AfterSaleMapper afterSaleMapper) {
        this.orderMapper = orderMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    @Override
    public String name() {
        return "create_after_sale";
    }

    @Override
    public String description() {
        return "为指定订单创建售后工单并发起退款（资金侧敏感操作，执行前必须经过用户确认）。"
                + "仅在用户明确要求对某笔订单退款/退货/换货/维修时调用";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "订单号"),
                ToolParameter.optionalString("reason", "售后原因，如退款/换货/维修说明")
        );
    }

    /**
     * 敏感操作：退款有资金副作用，必须人工确认后才执行
     */
    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));
        String reason = String.valueOf(arguments.getOrDefault("reason", "未说明原因"));

        OrderEntity order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return ToolResult.failure(name(), "未找到订单，无法创建售后: " + orderNo);
        }

        String status = order.getStatus();
        // 幂等：已退款 / 退款中，不重复发起
        if (OrderStatus.REFUNDED.name().equalsIgnoreCase(status)) {
            return ToolResult.success(name(), String.format("""
                    {"orderNo": "%s", "status": "%s", "message": "该订单此前已退款，无需重复申请"}""",
                    order.getOrderNo(), OrderStatus.REFUNDED.label()));
        }
        if (OrderStatus.REFUNDING.name().equalsIgnoreCase(status)) {
            return ToolResult.success(name(), String.format("""
                    {"orderNo": "%s", "status": "%s", "message": "该订单售后退款正在处理中，无需重复申请"}""",
                    order.getOrderNo(), OrderStatus.REFUNDING.label()));
        }
        // 不可退款的状态：已取消（走取消退款）、待付款（尚未支付，无款可退）
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(status)) {
            return ToolResult.failure(name(), "订单已取消，退款随取消流程处理，无需再申请售后: " + orderNo);
        }
        if (OrderStatus.CREATED.name().equalsIgnoreCase(status)) {
            return ToolResult.failure(name(), "订单尚未支付，无款可退，无法申请售后: " + orderNo);
        }

        // 真实副作用：把订单状态推进到"退款中"
        int rows = orderMapper.updateStatusByOrderNo(orderNo, OrderStatus.REFUNDING.name());
        if (rows <= 0) {
            return ToolResult.failure(name(), "创建售后失败，状态未更新: " + orderNo);
        }

        // 后置校验：回查确认真的进入退款中，而不是只看 UPDATE 影响行数
        OrderEntity verified = orderMapper.selectByOrderNo(orderNo);
        if (verified == null || !OrderStatus.REFUNDING.name().equalsIgnoreCase(verified.getStatus())) {
            return ToolResult.failure(name(),
                    "售后校验未通过：回查状态未变为退款中，订单 " + orderNo);
        }

        // 落库售后记录：query_after_sale 依赖此表查询进度，process_refund 依赖此表推进到账
        String afterSaleNo = "AS" + order.getOrderNo();
        AfterSaleEntity afterSale = new AfterSaleEntity();
        afterSale.setId(UUID.randomUUID().toString().replace("-", ""));
        afterSale.setAfterSaleNo(afterSaleNo);
        afterSale.setOrderNo(order.getOrderNo());
        afterSale.setCustomerId(order.getCustomerId());
        afterSale.setType("退款");
        afterSale.setReason(reason);
        afterSale.setStatus(OrderStatus.REFUNDING.name());
        afterSale.setRefundAmount(order.getAmount());
        int inserted = afterSaleMapper.insert(afterSale);
        if (inserted <= 0) {
            return ToolResult.failure(name(), "售后记录写入失败: " + orderNo);
        }

        String afterSaleInfo = String.format("""
                {
                    "afterSaleNo": "%s",
                    "orderNo": "%s",
                    "type": "退款",
                    "reason": "%s",
                    "previousStatus": "%s",
                    "status": "%s",
                    "refundAmount": %s,
                    "refundChannel": "原路退回",
                    "estimatedRefundDate": "2026-09-19",
                    "verified": true,
                    "message": "售后工单已创建，订单进入退款中，退款将在3个工作日内原路退回"
                }""",
                afterSaleNo,
                order.getOrderNo(),
                reason,
                OrderStatus.labelOf(order.getStatus()),
                OrderStatus.REFUNDING.label(),
                order.getAmount());

        return ToolResult.success(name(), afterSaleInfo);
    }
}
