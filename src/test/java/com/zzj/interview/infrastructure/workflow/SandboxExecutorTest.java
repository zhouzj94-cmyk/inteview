package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 沙箱执行器单元测试
 *
 * 测试要点：
 * 1. Dry-run模式：只验证不执行
 * 2. Mock模式：返回模拟结果
 * 3. Real模式：实际执行
 * 4. 执行历史记录
 */
@DisplayName("沙箱执行器测试")
class SandboxExecutorTest {

    private SandboxExecutor sandboxExecutor;

    @BeforeEach
    void setUp() {
        BusinessTool mockTool = new BusinessTool() {
            @Override
            public String name() { return "test-tool"; }
            @Override
            public String description() { return "测试工具"; }
            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.success("test-tool", "执行成功");
            }
        };
        sandboxExecutor = new SandboxExecutor(List.of(mockTool));
    }

    @Test
    @DisplayName("Dry-run模式 - 工具存在时返回验证信息")
    void execute_dryRun_toolExists() {
        ToolResult result = sandboxExecutor.execute("test-tool", Map.of("key", "value"),
                SandboxExecutor.Mode.DRY_RUN);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("DryRun"));
        assertTrue(result.getData().contains("test-tool"));
    }

    @Test
    @DisplayName("Dry-run模式 - 工具不存在时返回失败")
    void execute_dryRun_toolNotExists() {
        ToolResult result = sandboxExecutor.execute("non-existent", Map.of(),
                SandboxExecutor.Mode.DRY_RUN);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("不存在"));
    }

    @Test
    @DisplayName("Mock模式 - 返回订单模拟数据")
    void execute_mock_orderTool() {
        ToolResult result = sandboxExecutor.execute("order-query", Map.of("orderId", "123"),
                SandboxExecutor.Mode.MOCK);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("orderId") || result.getData().contains("MOCK"));
    }

    @Test
    @DisplayName("Mock模式 - 返回物流模拟数据")
    void execute_mock_logisticsTool() {
        ToolResult result = sandboxExecutor.execute("logistics-query", Map.of(),
                SandboxExecutor.Mode.MOCK);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("trackingNo") || result.getData().contains("运输"));
    }

    @Test
    @DisplayName("Real模式 - 实际执行工具")
    void execute_real_success() {
        ToolResult result = sandboxExecutor.execute("test-tool", Map.of(),
                SandboxExecutor.Mode.REAL);

        assertTrue(result.isSuccess());
        assertEquals("执行成功", result.getData());
    }

    @Test
    @DisplayName("Real模式 - 工具不存在时返回失败")
    void execute_real_toolNotExists() {
        ToolResult result = sandboxExecutor.execute("non-existent", Map.of(),
                SandboxExecutor.Mode.REAL);

        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("执行历史 - 记录每次执行")
    void executionHistory_recorded() {
        sandboxExecutor.clearHistory();

        sandboxExecutor.execute("test-tool", Map.of(), SandboxExecutor.Mode.DRY_RUN);
        sandboxExecutor.execute("test-tool", Map.of(), SandboxExecutor.Mode.MOCK);
        sandboxExecutor.execute("non-existent", Map.of(), SandboxExecutor.Mode.REAL);

        List<SandboxExecutor.SandboxRecord> history = sandboxExecutor.getExecutionHistory();
        assertEquals(3, history.size());
    }

    @Test
    @DisplayName("执行历史 - 记录包含正确信息")
    void executionHistory_containsCorrectInfo() {
        sandboxExecutor.clearHistory();

        sandboxExecutor.execute("test-tool", Map.of("arg1", "val1"), SandboxExecutor.Mode.MOCK);

        List<SandboxExecutor.SandboxRecord> history = sandboxExecutor.getExecutionHistory();
        assertEquals(1, history.size());

        SandboxExecutor.SandboxRecord record = history.get(0);
        assertEquals("test-tool", record.getToolName());
        assertEquals(SandboxExecutor.Mode.MOCK, record.getMode());
        assertEquals("val1", record.getArguments().get("arg1"));
        assertNotNull(record.getExecutionId());
        assertTrue(record.getTimestamp() > 0);
    }

    @Test
    @DisplayName("清空执行历史")
    void clearHistory_emptiesHistory() {
        sandboxExecutor.execute("test-tool", Map.of(), SandboxExecutor.Mode.MOCK);
        assertFalse(sandboxExecutor.getExecutionHistory().isEmpty());

        sandboxExecutor.clearHistory();
        assertTrue(sandboxExecutor.getExecutionHistory().isEmpty());
    }
}
