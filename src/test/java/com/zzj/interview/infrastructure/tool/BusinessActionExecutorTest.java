package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessActionExecutor 单元测试
 *
 * 覆盖：必填参数校验、敏感操作标记、执行成功/异常兜底、未知工具。
 * 用桩工具（不依赖 Spring/DB）隔离验证执行器自身逻辑。
 */
@DisplayName("BusinessActionExecutor测试")
class BusinessActionExecutorTest {

    /** 只读查询桩：必填 orderNo */
    static class QueryStub implements BusinessTool {
        @Override public String name() { return "query_order"; }
        @Override public String description() { return "查询订单"; }
        @Override public List<ToolParameter> parameters() {
            return List.of(ToolParameter.requiredString("orderNo", "订单号"));
        }
        @Override public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success(name(), "order=" + arguments.get("orderNo"));
        }
    }

    /** 敏感操作桩：requiresConfirmation=true */
    static class CancelStub implements BusinessTool {
        @Override public String name() { return "cancel_order"; }
        @Override public String description() { return "取消订单"; }
        @Override public boolean requiresConfirmation() { return true; }
        @Override public List<ToolParameter> parameters() {
            return List.of(
                    ToolParameter.requiredString("orderNo", "订单号"),
                    ToolParameter.optionalString("reason", "原因"));
        }
        @Override public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success(name(), "cancelled=" + arguments.get("orderNo"));
        }
    }

    /** 抛异常桩：验证执行器异常兜底 */
    static class BoomStub implements BusinessTool {
        @Override public String name() { return "boom"; }
        @Override public String description() { return "总是抛异常"; }
        @Override public ToolResult execute(Map<String, Object> arguments) {
            throw new RuntimeException("底层炸了");
        }
    }

    private BusinessActionExecutor executor() {
        return new BusinessActionExecutor(List.of(new QueryStub(), new CancelStub(), new BoomStub()));
    }

    @Test
    @DisplayName("参数齐全时正常执行并返回成功")
    void execute_withRequiredParams_succeeds() {
        ToolResult r = executor().execute("query_order", Map.of("orderNo", "10086"));
        assertTrue(r.isSuccess());
        assertEquals("order=10086", r.getData());
    }

    @Test
    @DisplayName("缺必填参数时不执行工具，直接返回失败")
    void execute_missingRequiredParam_failsWithoutCallingTool() {
        ToolResult r = executor().execute("query_order", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("orderNo"), "错误信息应指出缺失参数，实际=" + r.getErrorMessage());
    }

    @Test
    @DisplayName("必填参数为空白串也算缺失")
    void missingRequiredParams_blankCountsAsMissing() {
        List<String> missing = executor().missingRequiredParams("cancel_order",
                Map.of("orderNo", "  "));
        assertEquals(List.of("orderNo"), missing);
    }

    @Test
    @DisplayName("可选参数缺失不算缺参")
    void missingRequiredParams_optionalNotRequired() {
        List<String> missing = executor().missingRequiredParams("cancel_order", Map.of("orderNo", "10086"));
        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("敏感操作 requiresConfirmation 正确识别")
    void requiresConfirmation_identifiesSensitiveTool() {
        assertTrue(executor().requiresConfirmation("cancel_order"));
        assertFalse(executor().requiresConfirmation("query_order"));
        assertFalse(executor().requiresConfirmation("not_exist"));
    }

    @Test
    @DisplayName("未知工具返回失败而非抛异常")
    void execute_unknownTool_returnsFailure() {
        ToolResult r = executor().execute("no_such_tool", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("未找到工具"));
        assertFalse(executor().isKnown("no_such_tool"));
        assertTrue(executor().isKnown("query_order"));
    }

    @Test
    @DisplayName("工具抛异常被兜底为失败结果，不穿透编排")
    void execute_toolThrows_isContainedAsFailure() {
        ToolResult r = executor().execute("boom", Map.of());
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMessage().contains("工具执行异常"), "实际=" + r.getErrorMessage());
    }

    @Test
    @DisplayName("未知工具的 missingRequiredParams 返回空（交给 execute 报未找到）")
    void missingRequiredParams_unknownTool_returnsEmpty() {
        assertTrue(executor().missingRequiredParams("no_such_tool", Map.of()).isEmpty());
    }
}
