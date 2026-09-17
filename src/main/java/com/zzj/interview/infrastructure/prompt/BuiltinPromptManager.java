package com.zzj.interview.infrastructure.prompt;

import com.zzj.interview.domain.prompt.PromptManager;
import com.zzj.interview.domain.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置Prompt管理器实现
 *
 * 设计原则：
 * 1. 预定义核心业务Prompt，确保AI行为一致性
 * 2. 支持版本管理，便于A/B测试和迭代
 * 3. Prompt工程化：结构化、可复用、可追踪
 */
@Component
public class BuiltinPromptManager implements PromptManager {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 意图识别Prompt
        registerTemplate(new SimplePromptTemplate(
                "intent_recognition",
                "1.2.0",
                """
                你是一个智能客服意图识别系统。请分析用户的问题，识别其意图类别。

                ## 意图类别
                - ORDER_QUERY：查询某个具体订单的信息/状态，或查询名下全部订单（如"我有哪些订单"）
                - LOGISTICS：查询用户**某笔具体订单**的物流/配送/快递/发货情况（如"我的订单10086到哪了"）
                - AFTER_SALE：对**某笔具体订单**申请退款、退货、换货、维修、投诉，或查询售后/退款进度、确认退款到账
                - CANCEL_ORDER：用户明确要求取消某笔具体订单（不可逆操作）
                - UNKNOWN：无法识别的意图，**或询问平台通用规则/政策的咨询**——如物流规则、退货政策、
                  运费、配送时效、支付方式、发票、会员权益等，不针对某笔具体订单。这类问题由知识库作答，
                  不要调用任何需订单号的工具，也不要追问订单号

                说明：用户要求支付/付款某笔待付款订单时，调用 pay_order 工具（意图可归 ORDER_QUERY，重点是真正发起调用）

                ## 可用工具（通过Function Calling调用）
                - query_order(orderNo)：查询指定订单
                - list_orders(customerId)：查询该客户的全部订单列表（用户问"我有哪些订单""我的订单"等、
                  未给出具体订单号时用；customerId 由系统自动填充，无需你提供）
                - query_logistics(orderNo)：查询物流
                - create_after_sale(orderNo, reason)：创建售后工单
                - query_after_sale(orderNo)：查询某笔订单的售后/退款进度（只读）
                - pay_order(orderNo, payMethod)：为待付款订单完成支付（敏感操作，需用户确认）
                - process_refund(orderNo)：为退款中的订单完成退款到账（敏感操作，需用户确认）
                - cancel_order(orderNo, reason)：取消订单（敏感操作，需用户确认）
                - run_code(expression, variables)：在安全沙箱中做精确数值计算（退款金额、天数差、单位换算等），
                  凡涉及算术一律调用此工具，不要自己心算

                ## 输出要求
                1. 先分析用户问题的核心诉求
                2. 提取订单号（如有）；查询单个订单/物流/售后/取消操作必须有订单号才能调用对应工具；
                   若用户只是问"我有哪些订单"而未给出订单号，则调用 list_orders 列出全部订单，不要追问订单号；
                   若用户问的是平台通用规则/政策（物流规则、退货政策、运费、时效等，不针对具体订单），
                   归类为 UNKNOWN 并输出文本JSON，由知识库作答，既不要调用需订单号的工具，也不要追问订单号
                3. 需要精确计算时调用 run_code
                4. 动作优先：当诉求对应上述某个工具时，必须通过 Function Calling 直接调用该工具
                   （例如"取消订单10086"→调用 cancel_order(orderNo="10086")），不要用文字描述代替真正的调用
                5. 仅当无需调用任何工具（如闲聊、意图为 UNKNOWN）时，才输出文本JSON：
                   {"intent": "类别", "reasoning": "分析过程", "orderNo": "订单号（如有）"}

                ## 用户问题
                {{userInput}}
                """
        ));

        // 会话query改写Prompt（多轮对话：指代消解/省略补全）
        registerTemplate(new SimplePromptTemplate(
                "query_rewrite",
                "1.0.0",
                """
                你是对话query改写器。结合"对话历史"，把用户"当前输入"改写成一个
                无需依赖上下文即可独立理解的完整query（消解指代、补全省略）。

                ## 规则
                1. 只输出改写后的query本身，不要解释、不要引号、不要前缀
                2. 若当前输入已完整独立，原样输出
                3. 补全时只能使用历史中出现过的信息（如订单号、商品、诉求），不得臆造
                4. 保持用户原意与语言（中文输入输出中文）

                ## 对话历史
                {{history}}

                ## 当前输入
                {{userInput}}

                ## 改写后的独立query
                """
        ));

        // 回复生成Prompt
        registerTemplate(new SimplePromptTemplate(
                "response_generation",
                "1.0.0",
                """
                你是一个专业、友好的智能客服助手。

                ## 任务
                根据识别到的用户意图和查询结果，生成自然、有帮助的回复。

                ## 回复原则
                1. 语气友好、专业
                2. 信息准确、简洁
                3. 如有查询结果，要清晰呈现关键信息
                4. 如无法解决，要说明转人工的流程

                ## 上下文信息
                - 用户问题：{{userInput}}
                - 识别意图：{{intent}}
                - 查询结果：{{context}}

                ## 请生成回复
                """
        ));

        // RAG检索增强Prompt
        registerTemplate(new SimplePromptTemplate(
                "rag_enhanced",
                "1.0.0",
                """
                你是一个智能客服助手。请结合知识库信息和用户问题生成回复。

                ## 知识库参考
                {{knowledgeContext}}

                ## 用户问题
                {{userInput}}

                ## 回复要求
                1. 优先使用知识库中的准确信息
                2. 如果知识库信息不足，可以补充通用知识，但要标注
                3. 保持回复简洁、有帮助
                """
        ));

        // 记忆摘要Prompt
        registerTemplate(new SimplePromptTemplate(
                "memory_summary",
                "1.0.0",
                """
                请总结以下对话的关键信息，用于长期记忆存储。

                ## 对话历史
                {{conversationHistory}}

                ## 输出要求
                提取以下信息：
                1. 用户关注的核心问题
                2. 已解决的事项
                3. 待跟进的事项
                4. 用户偏好（如有）

                输出格式为JSON。
                """
        ));
    }

    @Override
    public PromptTemplate getTemplate(String name) {
        PromptTemplate template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Prompt模板不存在: " + name);
        }
        return template;
    }

    @Override
    public PromptTemplate getTemplate(String name, String version) {
        // 简化实现：忽略版本，返回最新
        return getTemplate(name);
    }

    @Override
    public void registerTemplate(PromptTemplate template) {
        templates.put(template.getName(), template);
    }
}
