package com.zzj.interview.domain.gateway;

import com.zzj.interview.domain.model.ticket.TicketIntent;

import java.util.List;
import java.util.Map;

/**
 * AI意图识别端口（Domain层定义的反腐层接口）
 *
 * 这是六边形架构中"端口"的概念：
 * - Domain只声明"我需要一个意图识别能力"
 * - 不关心底层用的是Qwen、GPT还是本地模型
 * - 不关心HTTP调用、API Key等基础设施细节
 *
 * 面试话术：
 * "AI是外部技术能力，不属于领域模型本身。
 *  Domain定义能力接口，Infrastructure提供实现，
 *  后续切换模型不影响领域层。"
 */
public interface AiIntentRecognizer {

    /**
     * 识别用户内容的意图（简单模式）
     */
    IntentRecognitionResult recognize(String content);

    /**
     * 根据上下文生成自然语言回复（简单模式）
     */
    String generateResponse(String content, TicketIntent intent, List<String> context);

    /**
     * 带上下文的意图识别（支持记忆、RAG、监控）
     */
    default IntentRecognitionResult recognize(String content, ProcessContext ctx) {
        return recognize(content);
    }

    /**
     * 带上下文的回复生成（支持记忆、RAG、监控）
     */
    default String generateResponse(String content, TicketIntent intent,
                                     List<String> context, ProcessContext ctx) {
        return generateResponse(content, intent, context);
    }

    /**
     * 会话query改写（多轮对话核心能力）
     *
     * 结合会话历史，把用户的原始输入（可能含指代/省略，如"那它什么时候到"）
     * 改写为上下文自洽的独立query（如"订单ORD123什么时候送达"），
     * 供意图识别、RAG检索、回复生成使用，提升多轮场景准确率。
     *
     * 默认实现：原样返回（无历史/无改写能力的实现保持兼容）。
     */
    default String rewriteQuery(String rawQuery, ProcessContext ctx) {
        return rawQuery;
    }

    /**
     * AI处理上下文（会话ID、客户ID等）
     * 用于关联短期记忆、长期记忆和监控追踪
     */
    record ProcessContext(
            String sessionId,
            String customerId
    ) {}

    /**
     * 意图识别结果
     */
    record IntentRecognitionResult(
            TicketIntent intent,
            double confidence,
            String reasoning,
            List<ToolCall> toolCalls,
            boolean needsClarification,
            String clarificationQuestion,
            List<String> missingSlots
    ) {
        /**
         * 向后兼容构造器：无澄清需求
         */
        public IntentRecognitionResult(TicketIntent intent, double confidence,
                                       String reasoning, List<ToolCall> toolCalls) {
            this(intent, confidence, reasoning, toolCalls, false, null, List.of());
        }
    }

    /**
     * AI请求调用的业务工具
     * 这是Function Calling机制在领域层的抽象表达
     */
    record ToolCall(
            String toolName,
            Map<String, Object> arguments
    ) {}
}
