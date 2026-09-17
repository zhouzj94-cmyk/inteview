package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TicketState单元测试
 *
 * 测试要点：
 * 1. 状态值的读取
 * 2. 默认值处理
 * 3. Plan-and-Execute 新增字段（plan/游标/确认/验证）的读取
 */
@DisplayName("TicketState测试")
class TicketStateTest {

    @Test
    @DisplayName("读取基本状态值")
    void value_basicTypes() {
        Map<String, Object> data = new HashMap<>();
        data.put("ticketId", "T001");
        data.put("conversationId", "CV001");
        data.put("customerId", "C001");
        data.put("content", "查询订单");
        data.put("intent", "ORDER_QUERY");
        data.put("confidence", 0.95);
        data.put("response", "您的订单已发货");
        data.put("status", "HANDLED");

        TicketState state = new TicketState(data);

        assertEquals("T001", state.ticketId());
        assertEquals("CV001", state.conversationId());
        assertEquals("C001", state.customerId());
        assertEquals("查询订单", state.content());
        assertEquals("ORDER_QUERY", state.intent());
        assertEquals(0.95, state.confidence());
        assertEquals("您的订单已发货", state.response());
        assertEquals("HANDLED", state.status());
    }

    @Test
    @DisplayName("缺失字段返回默认值")
    void value_missingFields_returnDefaults() {
        Map<String, Object> data = new HashMap<>();
        TicketState state = new TicketState(data);

        assertEquals("", state.ticketId());
        assertEquals("", state.conversationId());
        assertEquals("", state.customerId());
        assertEquals("", state.content());
        assertEquals("UNKNOWN", state.intent());
        assertEquals(0.0, state.confidence());
        assertEquals("", state.reasoning());
        assertEquals("", state.response());
        assertEquals("CREATED", state.status());
        assertEquals(0, state.planCursor());
        assertEquals(0, state.replanCount());
        assertFalse(state.needsClarification());
        assertFalse(state.needsConfirm());
        assertFalse(state.approved());
        assertFalse(state.verified());
    }

    @Test
    @DisplayName("rewrittenQuery 缺失时回退到 content")
    void value_rewrittenQuery_fallsBackToContent() {
        Map<String, Object> data = new HashMap<>();
        data.put("content", "原始诉求");
        assertEquals("原始诉求", new TicketState(data).rewrittenQuery());

        data.put("rewrittenQuery", "改写后的独立query");
        assertEquals("改写后的独立query", new TicketState(data).rewrittenQuery());
    }

    @Test
    @DisplayName("读取规划步骤与当前游标步骤")
    void value_plan_and_currentStep() {
        Map<String, Object> data = new HashMap<>();
        data.put("plan", List.of(
                PlanStep.retrieve("查退货政策"),
                PlanStep.tool("cancel_order", Map.of("orderNo", "10086"))));
        data.put("planCursor", 1);

        TicketState state = new TicketState(data);

        assertEquals(2, state.plan().size());
        assertTrue(state.plan().get(0).isRetrieve());
        PlanStep cur = state.currentStep();
        assertNotNull(cur);
        assertTrue(cur.isTool());
        assertEquals("cancel_order", cur.toolName());
        assertEquals("10086", cur.arguments().get("orderNo"));
    }

    @Test
    @DisplayName("游标越界时 currentStep 返回 null")
    void value_currentStep_outOfBounds_returnsNull() {
        Map<String, Object> data = new HashMap<>();
        data.put("plan", List.of(PlanStep.retrieve("x")));
        data.put("planCursor", 5);
        assertNull(new TicketState(data).currentStep());
    }

    @Test
    @DisplayName("读取工具结果列表（ToolResult）")
    void value_toolResults() {
        Map<String, Object> data = new HashMap<>();
        data.put("toolResults", List.of(
                ToolResult.success("query_order", "{\"status\":\"已发货\"}"),
                ToolResult.failure("cancel_order", "取消失败")));

        TicketState state = new TicketState(data);

        assertEquals(2, state.toolResults().size());
        assertTrue(state.toolResults().get(0).isSuccess());
        assertFalse(state.toolResults().get(1).isSuccess());
        assertEquals("取消失败", state.toolResults().get(1).getErrorMessage());
    }

    @Test
    @DisplayName("空/null 列表字段返回空列表")
    void value_emptyAndNullLists_returnEmpty() {
        Map<String, Object> empty = new HashMap<>();
        TicketState s1 = new TicketState(empty);
        assertNotNull(s1.plan());
        assertTrue(s1.plan().isEmpty());
        assertNotNull(s1.toolResults());
        assertTrue(s1.toolResults().isEmpty());
        assertNotNull(s1.retrievedContext());
        assertTrue(s1.retrievedContext().isEmpty());
        assertNotNull(s1.missingSlots());
        assertTrue(s1.missingSlots().isEmpty());
        assertNotNull(s1.steps());
        assertTrue(s1.steps().isEmpty());

        Map<String, Object> nulled = new HashMap<>();
        nulled.put("toolResults", null);
        nulled.put("steps", null);
        nulled.put("plan", null);
        TicketState s2 = new TicketState(nulled);
        assertNotNull(s2.toolResults());
        assertTrue(s2.toolResults().isEmpty());
        assertNotNull(s2.steps());
        assertTrue(s2.steps().isEmpty());
        assertNotNull(s2.plan());
        assertTrue(s2.plan().isEmpty());
    }

    @Test
    @DisplayName("读取确认（HITL）字段")
    void value_confirmFields() {
        Map<String, Object> data = new HashMap<>();
        data.put("needsConfirm", true);
        data.put("pendingTool", "cancel_order");
        data.put("pendingArgs", Map.of("orderNo", "10086"));
        data.put("confirmPrompt", "确认取消订单 10086？");
        data.put("approved", true);

        TicketState state = new TicketState(data);

        assertTrue(state.needsConfirm());
        assertEquals("cancel_order", state.pendingTool());
        assertEquals("10086", state.pendingArgs().get("orderNo"));
        assertEquals("确认取消订单 10086？", state.confirmPrompt());
        assertTrue(state.approved());
    }
}
