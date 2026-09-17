package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.model.ticket.TicketIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 意图解析器（意图弱化为"派生标签"）
 *
 * 设计理念：假设 AI 团队已提供意图识别/对话能力，本业务系统真正的驱动信号
 * 是"要执行哪个业务动作"。因此意图不再作为编排的主输入，而是由所选工具
 * 反向派生出来的一个标签，仅用于落库统计、回复语气与长期记忆归类。
 *
 * 规则：
 * - 优先由选中的业务工具映射出权威意图（如 cancel_order → CANCEL_ORDER）
 * - 工具无映射（如 run_code 只是计算辅助）或没有工具时，回退到 AI 给出的意图
 * - 都没有则 UNKNOWN
 */
@Component
public class ActionIntentResolver {

    private static final Map<String, TicketIntent> TOOL_TO_INTENT = Map.of(
            "query_order", TicketIntent.ORDER_QUERY,
            "list_orders", TicketIntent.ORDER_QUERY,
            "query_logistics", TicketIntent.LOGISTICS,
            "cancel_order", TicketIntent.CANCEL_ORDER,
            "create_after_sale", TicketIntent.AFTER_SALE,
            "process_refund", TicketIntent.AFTER_SALE,
            "query_after_sale", TicketIntent.AFTER_SALE
    );

    /**
     * 由一组选中的工具派生意图标签。
     *
     * @param selectedTools 规划选中的工具名（按执行顺序）
     * @param aiIntent      AI 识别给出的意图，作为回退
     */
    public TicketIntent resolve(List<String> selectedTools, TicketIntent aiIntent) {
        if (selectedTools != null) {
            for (String t : selectedTools) {
                TicketIntent mapped = TOOL_TO_INTENT.get(t);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        return aiIntent != null ? aiIntent : TicketIntent.UNKNOWN;
    }

    /** 单工具版本：便于确认/执行单个动作时派生标签 */
    public TicketIntent resolveOne(String toolName, TicketIntent aiIntent) {
        TicketIntent mapped = toolName == null ? null : TOOL_TO_INTENT.get(toolName);
        return mapped != null ? mapped : (aiIntent != null ? aiIntent : TicketIntent.UNKNOWN);
    }
}
