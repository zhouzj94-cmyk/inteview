package com.zzj.interview.domain.tool;

import java.io.Serializable;

/**
 * 工具执行结果
 *
 * 实现 Serializable：本对象会作为 Plan-and-Execute 图状态的一部分写入
 * LangGraph4j 检查点（MemorySaver 采用 Java 序列化持久化状态），
 * 因此必须可序列化，否则人工确认挂起/恢复会抛 NotSerializableException。
 */
public class ToolResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String toolName;
    private final boolean success;
    private final String data;
    private final String errorMessage;

    private ToolResult(String toolName, boolean success, String data, String errorMessage) {
        this.toolName = toolName;
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    public static ToolResult success(String toolName, String data) {
        return new ToolResult(toolName, true, data, null);
    }

    public static ToolResult failure(String toolName, String errorMessage) {
        return new ToolResult(toolName, false, null, errorMessage);
    }

    public String getToolName() { return toolName; }
    public boolean isSuccess() { return success; }
    public String getData() { return data; }
    public String getErrorMessage() { return errorMessage; }
}
