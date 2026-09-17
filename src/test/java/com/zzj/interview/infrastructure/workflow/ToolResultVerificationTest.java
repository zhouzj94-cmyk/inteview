package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编排级「结果验证」上下文构建测试
 *
 * 验证 VerifiedContextBuilder 修掉了旧bug：此前失败工具的结果被静默丢弃
 * （只 map(getData).filter(nonNull)），导致 AI 看不到失败、凭空宣称成功。
 * 现在：成功→纳入数据；失败→纳入显式失败说明。
 */
@DisplayName("工具结果验证上下文构建测试")
class ToolResultVerificationTest {

    @Test
    @DisplayName("成功结果 - 纳入其数据")
    void success_includesData() {
        List<String> ctx = VerifiedContextBuilder.build(
                List.of(ToolResult.success("query_order", "{\"status\":\"已发货\"}")));

        assertEquals(1, ctx.size());
        assertEquals("{\"status\":\"已发货\"}", ctx.get(0));
    }

    @Test
    @DisplayName("失败结果 - 不再被丢弃，纳入显式失败说明")
    void failure_includesErrorMessage() {
        List<String> ctx = VerifiedContextBuilder.build(
                List.of(ToolResult.failure("cancel_order", "未找到订单")));

        assertEquals(1, ctx.size());
        assertTrue(ctx.get(0).contains("cancel_order"));
        assertTrue(ctx.get(0).contains("执行失败"));
        assertTrue(ctx.get(0).contains("未找到订单"));
    }

    @Test
    @DisplayName("成功但data为null - 跳过，不塞null")
    void successWithNullData_skipped() {
        List<String> ctx = VerifiedContextBuilder.build(
                List.of(ToolResult.success("run_code", null)));

        assertTrue(ctx.isEmpty());
    }

    @Test
    @DisplayName("混合结果 - 成功与失败都在，且保持顺序")
    void mixed_preservesBothAndOrder() {
        List<String> ctx = VerifiedContextBuilder.build(Arrays.asList(
                ToolResult.success("query_order", "订单数据"),
                ToolResult.failure("logistics_query", "物流接口超时"),
                ToolResult.success("run_code", "42")));

        assertEquals(3, ctx.size());
        assertEquals("订单数据", ctx.get(0));
        assertTrue(ctx.get(1).contains("物流接口超时"));
        assertEquals("42", ctx.get(2));
    }

    @Test
    @DisplayName("null列表与null元素 - 安全处理为空/跳过")
    void nullSafe() {
        assertTrue(VerifiedContextBuilder.build(null).isEmpty());

        List<String> ctx = VerifiedContextBuilder.build(Arrays.asList(
                null, ToolResult.success("query_order", "ok")));
        assertEquals(1, ctx.size());
        assertEquals("ok", ctx.get(0));
    }
}
