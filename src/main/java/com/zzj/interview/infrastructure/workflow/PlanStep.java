package com.zzj.interview.infrastructure.workflow;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Plan-and-Execute 的单个规划步骤。
 *
 * plan 节点产出一串 PlanStep，执行阶段按 planCursor 逐步推进：
 * - RETRIEVE：检索知识库（RAG），用于政策/FAQ/咨询类问题
 * - TOOL：调用业务工具（查询订单/取消订单/代码沙箱等）
 *
 * 规划与执行分离，才能显式表达"这一步是查知识库还是调工具"，
 * 也让图的边可以按步骤类型精确路由。
 *
 * 实现 Serializable：作为图状态写入 LangGraph4j 检查点（Java 序列化），
 * 人工确认挂起/恢复依赖它可序列化。arguments 统一规整为 HashMap（可序列化）。
 * 不可变记录：节点每步返回新的 plan 列表，不就地修改，避免跨检查点引用污染。
 */
public record PlanStep(Kind kind, String toolName, Map<String, Object> arguments, String note)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** 检索知识库（RAG） */
        RETRIEVE,
        /** 调用业务工具 */
        TOOL
    }

    public static PlanStep retrieve(String note) {
        return new PlanStep(Kind.RETRIEVE, null, new HashMap<>(), note);
    }

    public static PlanStep tool(String toolName, Map<String, Object> arguments) {
        return new PlanStep(Kind.TOOL, toolName,
                arguments != null ? new HashMap<>(arguments) : new HashMap<>(), toolName);
    }

    public boolean isTool() {
        return kind == Kind.TOOL;
    }

    public boolean isRetrieve() {
        return kind == Kind.RETRIEVE;
    }
}

