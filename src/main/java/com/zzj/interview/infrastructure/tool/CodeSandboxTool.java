package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.sandbox.SafeExpressionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 代码沙箱工具（暴露给AI的Function Calling）
 *
 * 让AI在需要精确计算时（退款金额、天数差、单位换算、比较大小等）
 * 生成一段受限表达式，由 {@link SafeExpressionEngine} 在安全边界内求值，
 * 避免让大模型"心算"导致的数值错误。
 *
 * 只读、无副作用，因此 requiresConfirmation=false。
 */
@Component
public class CodeSandboxTool implements BusinessTool {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxTool.class);

    private final SafeExpressionEngine engine = new SafeExpressionEngine();
    private final ObjectMapper objectMapper;

    public CodeSandboxTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "run_code";
    }

    @Override
    public String description() {
        return "在安全沙箱中计算一个数学表达式，用于精确数值计算。"
                + "支持 + - * / % 括号、比较(== != < <= > >=)、"
                + "内置函数(abs sqrt floor ceil round log sin cos tan exp pow min max)，以及命名变量。"
                + "示例：expression=\"(299*0.8)+12\"；或 expression=\"max(a,b)-min(a,b)\", variables=\"{\\\"a\\\":12,\\\"b\\\":7}\"";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.requiredString("expression", "要求值的受限表达式，如 (299*0.8)+12"),
                // 用string承载JSON而非object：LangChain4j 0.36.2序列化无properties的object会NPE
                ToolParameter.optionalString("variables",
                        "可选的数值变量表，JSON字符串格式，如 \"{\\\"a\\\":299,\\\"b\\\":0.8}\"")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments) {
        Object exprObj = arguments.get("expression");
        if (exprObj == null) {
            // 兼容AI可能用的别名
            exprObj = arguments.getOrDefault("code", arguments.get("expr"));
        }
        if (exprObj == null || String.valueOf(exprObj).isBlank()) {
            return ToolResult.failure(name(), "缺少表达式参数 expression");
        }
        String expression = String.valueOf(exprObj);

        Map<String, Object> variables = extractVariables(arguments.get("variables"));

        SafeExpressionEngine.EvalResult result = engine.evaluate(expression, variables);
        if (result.success()) {
            log.info("[沙箱] 计算成功: {} = {} ({}ms)", expression, result.value(), result.elapsedMs());
            String data = String.format("{\"expression\":\"%s\",\"result\":\"%s\",\"elapsedMs\":%d}",
                    escape(expression), escape(result.value()), result.elapsedMs());
            return ToolResult.success(name(), data);
        }
        log.warn("[沙箱] 计算失败: {} -> {}", expression, result.error());
        return ToolResult.failure(name(), "沙箱执行失败: " + result.error());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractVariables(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                log.warn("[沙箱] 变量解析失败，按无变量处理: {}", s);
                return Map.of();
            }
        }
        return Map.of();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
