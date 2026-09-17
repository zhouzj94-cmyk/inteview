package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.persistence.entity.AfterSaleEntity;
import com.zzj.interview.infrastructure.persistence.entity.OrderStatus;
import com.zzj.interview.infrastructure.persistence.mapper.AfterSaleMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 售后进度查询工具（只读，无需确认）
 *
 * 按订单号查询 after_sale 表中的售后记录，返回售后单号、类型、原因、状态、退款金额与时间，
 * 用于回答"我的退款到哪一步了""售后进度怎么样"这类问题。
 */
@Component
public class QueryAfterSaleTool implements BusinessTool {

    private final AfterSaleMapper afterSaleMapper;

    public QueryAfterSaleTool(AfterSaleMapper afterSaleMapper) {
        this.afterSaleMapper = afterSaleMapper;
    }

    @Override
    public String name() {
        return "query_after_sale";
    }

    @Override
    public String description() {
        return "根据订单号查询售后/退款进度，包括售后单号、类型、状态、退款金额等";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("orderNo", "订单号")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.getOrDefault("orderNo", "UNKNOWN"));

        AfterSaleEntity afterSale = afterSaleMapper.selectByOrderNo(orderNo);
        if (afterSale == null) {
            return ToolResult.failure(name(), "未找到订单的售后记录: " + orderNo);
        }

        String info = String.format("""
                {
                    "afterSaleNo": "%s",
                    "orderNo": "%s",
                    "type": "%s",
                    "reason": "%s",
                    "status": "%s",
                    "statusCode": "%s",
                    "refundAmount": %s,
                    "refundChannel": "原路退回",
                    "createdAt": "%s",
                    "updatedAt": "%s"
                }""",
                afterSale.getAfterSaleNo(),
                afterSale.getOrderNo(),
                afterSale.getType(),
                afterSale.getReason(),
                OrderStatus.labelOf(afterSale.getStatus()),
                afterSale.getStatus(),
                afterSale.getRefundAmount(),
                afterSale.getCreatedAt(),
                afterSale.getUpdatedAt());

        return ToolResult.success(name(), info);
    }
}
