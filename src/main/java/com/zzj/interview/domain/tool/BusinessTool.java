package com.zzj.interview.domain.tool;

import java.util.List;
import java.util.Map;

/**
 * 业务工具接口（Function Calling的工具定义）
 *
 * 当AI通过Function Calling决定调用某个业务方法时，
 * 由实现了此接口的工具来执行具体的业务逻辑。
 *
 * 架构位置：
 * - 接口定义在Domain层（领域能力抽象）
 * - 实现在Infrastructure层（具体业务工具）
 * - 由基础设施层的业务动作执行器统一调度（含必填参数校验与敏感动作确认门控）
 *
 * 面试要点：
 * "AI不直接调用Service，而是通过工具抽象层间接调用，
 *  这样工具的增减不影响AI集成代码"
 */
public interface BusinessTool {

    /**
     * 工具名称，与AI的Function Calling中function name对应
     */
    String name();

    /**
     * 工具描述，会提供给AI帮助其理解工具用途
     */
    String description();

    /**
     * 工具参数声明（用于生成Function Calling的JSON Schema）
     *
     * 领域层只描述"有哪些参数、什么类型、是否必填"，
     * 不依赖任何AI框架；基础设施层负责把它适配成
     * LangChain4j的ToolSpecification。
     */
    default List<ToolParameter> parameters() {
        return List.of();
    }

    /**
     * 是否需要人工确认后才能执行（人机交互HITL）
     *
     * 只读查询类工具返回false（默认）；
     * 会产生副作用的敏感操作（如取消订单、退款）应重写为true，
     * 编排层据此在执行前暂停并向用户请求确认。
     */
    default boolean requiresConfirmation() {
        return false;
    }

    /**
     * 执行工具
     *
     * @param arguments AI传入的参数（从Function Calling的arguments解析而来）
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> arguments);

    /**
     * 工具参数描述（领域层中立表达，不绑定具体AI框架）
     *
     * @param name        参数名
     * @param type        JSON Schema类型：string/integer/number/boolean
     * @param description 参数用途说明，提供给AI
     * @param required    是否必填
     */
    record ToolParameter(
            String name,
            String type,
            String description,
            boolean required
    ) {
        public static ToolParameter requiredString(String name, String description) {
            return new ToolParameter(name, "string", description, true);
        }

        public static ToolParameter optionalString(String name, String description) {
            return new ToolParameter(name, "string", description, false);
        }
    }
}
