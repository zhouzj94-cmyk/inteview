package com.zzj.interview.infrastructure.tool;

import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.BusinessTool.ToolParameter;
import com.zzj.interview.domain.tool.ToolResult;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j工具适配器
 *
 * 职责：把领域层的BusinessTool端口适配成LangChain4j的ToolSpecification，
 * 并负责执行AI返回的ToolExecutionRequest。
 *
 * 六边形架构体现：
 * - BusinessTool是Domain定义的端口（不依赖任何AI框架）
 * - 本适配器是Infrastructure的"被驱动适配器"，引入LangChain4j技术细节
 * - 切换AI框架（LangChain4j → 其他）只需改这里，领域工具不受影响
 *
 * 面试话术：
 * "工具的领域定义和AI框架的ToolSpecification之间用适配器解耦，
 *  Function Calling的schema由领域参数元数据自动生成，避免硬编码。"
 */
@Component
public class LangChain4jToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolAdapter.class);

    private final Map<String, BusinessTool> toolMap = new LinkedHashMap<>();
    private final List<ToolSpecification> specifications = new ArrayList<>();
    private final ObjectMapper objectMapper;

    public LangChain4jToolAdapter(List<BusinessTool> businessTools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (BusinessTool tool : businessTools) {
            toolMap.put(tool.name(), tool);
            specifications.add(toSpecification(tool));
        }
        log.info("LangChain4j工具适配器初始化完成，注册工具: {}", toolMap.keySet());
    }

    /**
     * 把领域BusinessTool转为LangChain4j ToolSpecification
     * 参数schema由ToolParameter元数据自动生成
     */
    private ToolSpecification toSpecification(BusinessTool tool) {
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description());

        for (ToolParameter param : tool.parameters()) {
            JsonSchemaProperty typeProp = JsonSchemaProperty.type(param.type());
            JsonSchemaProperty descProp = JsonSchemaProperty.description(param.description());
            if (param.required()) {
                builder.addParameter(param.name(), typeProp, descProp);
            } else {
                builder.addOptionalParameter(param.name(), typeProp, descProp);
            }
        }
        return builder.build();
    }

    /**
     * 提供给ChatLanguageModel.generate()的工具规格列表
     */
    public List<ToolSpecification> specifications() {
        return List.copyOf(specifications);
    }

    /**
     * 执行AI返回的工具调用请求
     *
     * @param request LangChain4j的工具执行请求（含name和JSON arguments）
     * @return 工具执行结果的文本（成功返回data，失败返回错误信息），用于回填ToolExecutionResultMessage
     */
    public String execute(ToolExecutionRequest request) {
        BusinessTool tool = toolMap.get(request.name());
        if (tool == null) {
            log.warn("AI请求了未注册的工具: {}", request.name());
            return "{\"error\":\"未找到工具: " + request.name() + "\"}";
        }
        Map<String, Object> arguments = parseArguments(request.arguments());
        try {
            ToolResult result = tool.execute(arguments);
            return result.isSuccess() ? result.getData() : result.getErrorMessage();
        } catch (Exception e) {
            log.error("工具执行异常: name={}", request.name(), e);
            return "{\"error\":\"工具执行异常: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 执行AI返回的工具调用请求，返回领域ToolResult（供需要结构化结果的调用方使用）
     */
    public ToolResult executeForResult(ToolExecutionRequest request) {
        BusinessTool tool = toolMap.get(request.name());
        if (tool == null) {
            return ToolResult.failure(request.name(), "未找到工具: " + request.name());
        }
        Map<String, Object> arguments = parseArguments(request.arguments());
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return ToolResult.failure(request.name(), "工具执行异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", json, e);
            return Map.of();
        }
    }
}
