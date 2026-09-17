package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱执行环境
 *
 * 设计场景：
 * 1. AI决策验证：在执行真实业务操作前，先在沙箱中验证AI的决策是否合理
 * 2. 工具调用测试：测试工具调用参数是否正确，不实际执行业务逻辑
 * 3. 回滚演练：模拟异常场景，验证系统的容错能力
 *
 * 使用方式：
 * - dryRun模式：只验证参数，不执行实际操作
 * - mock模式：返回预设的模拟结果
 * - 真实模式：实际执行（默认）
 */
@Component
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    private final Map<String, BusinessTool> toolRegistry = new ConcurrentHashMap<>();
    private final Map<String, SandboxRecord> executionHistory = new ConcurrentHashMap<>();

    public SandboxExecutor(List<BusinessTool> tools) {
        for (BusinessTool tool : tools) {
            toolRegistry.put(tool.name(), tool);
        }
        log.info("沙箱执行器初始化，注册{}个工具", toolRegistry.size());
    }

    /**
     * 沙箱执行模式
     */
    public enum Mode {
        DRY_RUN,    // 只验证，不执行
        MOCK,       // 返回模拟结果
        REAL        // 真实执行
    }

    /**
     * 在沙箱中执行工具调用
     *
     * @param toolName 工具名称
     * @param arguments 参数
     * @param mode 执行模式
     * @return 执行结果
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments, Mode mode) {
        String executionId = UUID.randomUUID().toString();
        SandboxRecord record = new SandboxRecord(executionId, toolName, arguments, mode);

        try {
            ToolResult result;
            switch (mode) {
                case DRY_RUN:
                    result = dryRunExecute(toolName, arguments);
                    record.setStatus("DRY_RUN_OK");
                    break;
                case MOCK:
                    result = mockExecute(toolName, arguments);
                    record.setStatus("MOCK_OK");
                    break;
                case REAL:
                default:
                    BusinessTool tool = toolRegistry.get(toolName);
                    if (tool == null) {
                        result = ToolResult.failure(toolName, "工具不存在: " + toolName);
                        record.setStatus("NOT_FOUND");
                    } else {
                        result = tool.execute(arguments);
                        record.setStatus(result.isSuccess() ? "REAL_OK" : "REAL_FAIL");
                    }
                    break;
            }

            record.setResult(result);
            executionHistory.put(executionId, record);
            log.info("[沙箱:{}] {} 执行{}: {}", executionId, toolName, mode, record.getStatus());
            return result;

        } catch (Exception e) {
            record.setStatus("ERROR");
            record.setError(e.getMessage());
            executionHistory.put(executionId, record);
            log.error("[沙箱:{}] {} 执行异常: {}", executionId, toolName, e.getMessage());
            return ToolResult.failure(toolName, e.getMessage());
        }
    }

    /**
     * Dry-run模式：只验证工具是否存在，不实际执行
     */
    private ToolResult dryRunExecute(String toolName, Map<String, Object> arguments) {
        BusinessTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return ToolResult.failure(toolName, "工具不存在");
        }
        String validation = String.format("[DryRun] 工具%s可用，参数: %s",
                toolName, arguments != null ? arguments.keySet() : "无");
        return ToolResult.success(toolName, validation);
    }

    /**
     * Mock模式：返回预设的模拟结果
     */
    private ToolResult mockExecute(String toolName, Map<String, Object> arguments) {
        String mockData = generateMockData(toolName, arguments);
        return ToolResult.success(toolName, mockData);
    }

    /**
     * 根据工具类型生成模拟数据
     */
    private String generateMockData(String toolName, Map<String, Object> arguments) {
        if (toolName.contains("order") || toolName.contains("订单")) {
            return "{\"orderId\":\"MOCK-001\",\"status\":\"已发货\",\"amount\":299.00}";
        } else if (toolName.contains("logistics") || toolName.contains("物流")) {
            return "{\"trackingNo\":\"SF1234567890\",\"status\":\"运输中\",\"estimatedDays\":2}";
        } else if (toolName.contains("aftersale") || toolName.contains("售后")) {
            return "{\"ticketId\":\"AS-001\",\"status\":\"已受理\",\"processTime\":\"24小时内\"}";
        }
        return "{\"message\":\"模拟执行成功\",\"tool\":\"" + toolName + "\"}";
    }

    /**
     * 获取执行历史
     */
    public List<SandboxRecord> getExecutionHistory() {
        return new ArrayList<>(executionHistory.values());
    }

    /**
     * 清空执行历史
     */
    public void clearHistory() {
        executionHistory.clear();
    }

    /**
     * 沙箱执行记录
     */
    public static class SandboxRecord {
        private final String executionId;
        private final String toolName;
        private final Map<String, Object> arguments;
        private final Mode mode;
        private String status;
        private ToolResult result;
        private String error;
        private final long timestamp;

        public SandboxRecord(String executionId, String toolName, Map<String, Object> arguments, Mode mode) {
            this.executionId = executionId;
            this.toolName = toolName;
            this.arguments = arguments;
            this.mode = mode;
            this.timestamp = System.currentTimeMillis();
        }

        public String getExecutionId() { return executionId; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public Mode getMode() { return mode; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public ToolResult getResult() { return result; }
        public void setResult(ToolResult result) { this.result = result; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public long getTimestamp() { return timestamp; }
    }
}
