package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务动作执行器
 *
 * 由原 TicketAsyncProcessor.ToolDispatcher 演进而来，是"AI 决策"与"业务工具"之间的反腐层：
 * - AI/规划层只给出工具名 + 参数，这里负责找到对应 BusinessTool 并安全执行
 * - 执行前做必填参数校验，缺参直接返回 failure，不把不完整参数丢给工具
 * - 统一异常兜底：任何工具抛异常都转成 ToolResult.failure，绝不让异常穿透编排
 *
 * 架构位置：接口 BusinessTool 在 Domain 层，本执行器在 Infrastructure 层。
 */
@Component
public class BusinessActionExecutor {

    private final Map<String, BusinessTool> toolMap;

    public BusinessActionExecutor(List<BusinessTool> tools) {
        this.toolMap = tools.stream()
                .collect(Collectors.toMap(BusinessTool::name, t -> t, (a, b) -> a));
    }

    /** 工具是否已注册 */
    public boolean isKnown(String toolName) {
        return toolName != null && toolMap.containsKey(toolName);
    }

    /** 该工具是否为敏感操作（需人工确认后才执行） */
    public boolean requiresConfirmation(String toolName) {
        BusinessTool tool = toolMap.get(toolName);
        return tool != null && tool.requiresConfirmation();
    }

    /**
     * 返回缺失的必填参数名（空列表表示参数齐全）。
     * 未知工具返回空列表——交给 execute 统一报 "未找到工具"。
     */
    public List<String> missingRequiredParams(String toolName, Map<String, Object> arguments) {
        BusinessTool tool = toolMap.get(toolName);
        if (tool == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (BusinessTool.ToolParameter p : tool.parameters()) {
            if (!p.required()) {
                continue;
            }
            Object v = arguments == null ? null : arguments.get(p.name());
            if (v == null || String.valueOf(v).isBlank()) {
                missing.add(p.name());
            }
        }
        return missing;
    }

    /**
     * 权威注入客户身份。
     *
     * customerId 是服务端上下文（当前登录客户），不是模型可自由填写的业务参数。
     * 若工具声明了 customerId 参数，这里用上下文值**覆盖**模型可能臆造的值，
     * 防止越权查询/操作他人订单。工具未声明 customerId 时原样返回。
     *
     * @return 注入后的新参数map（不修改入参）；无需注入时返回原 args
     */
    public Map<String, Object> injectCustomerId(String toolName, Map<String, Object> args, String customerId) {
        BusinessTool tool = toolName == null ? null : toolMap.get(toolName);
        if (tool == null || customerId == null || customerId.isBlank()) {
            return args;
        }
        boolean needsCustomer = tool.parameters().stream()
                .anyMatch(p -> "customerId".equals(p.name()));
        if (!needsCustomer) {
            return args;
        }
        Map<String, Object> merged = new java.util.HashMap<>(args != null ? args : Map.of());
        merged.put("customerId", customerId);
        return merged;
    }

    /**
     * 执行单个业务动作。
     * 未找到工具 / 缺必填参数 / 工具抛异常，均返回 ToolResult.failure，不抛出。
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        BusinessTool tool = toolMap.get(toolName);
        if (tool == null) {
            return ToolResult.failure(toolName, "未找到工具: " + toolName);
        }
        List<String> missing = missingRequiredParams(toolName, arguments);
        if (!missing.isEmpty()) {
            return ToolResult.failure(toolName, "缺少必填参数: " + String.join(",", missing));
        }
        try {
            return tool.execute(arguments != null ? arguments : Map.of());
        } catch (Exception e) {
            return ToolResult.failure(toolName, "工具执行异常: " + e.getMessage());
        }
    }
}
