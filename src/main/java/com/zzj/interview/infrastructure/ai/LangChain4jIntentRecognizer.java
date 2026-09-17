package com.zzj.interview.infrastructure.ai;

import com.zzj.interview.domain.gateway.AiIntentRecognizer;
import com.zzj.interview.domain.memory.UserMemory;
import com.zzj.interview.domain.memory.UserMemory.UserMemoryData;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.prompt.PromptManager;
import com.zzj.interview.domain.prompt.PromptTemplate;
import com.zzj.interview.domain.rag.KnowledgeBase;
import com.zzj.interview.domain.rag.KnowledgeChunk;
import com.zzj.interview.infrastructure.memory.ConversationMemoryChatMemoryStore;
import com.zzj.interview.infrastructure.tool.LangChain4jToolAdapter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于LangChain4j的AI意图识别器（Infrastructure层实现）
 *
 * 用LangChain4j的模型/工具/记忆抽象替代裸HTTP调用：
 * 1. AI调用     —— ChatLanguageModel.generate()（OpenAiChatModel指向DashScope）
 * 2. Function Calling —— ToolSpecification（由BusinessTool经适配器生成）+ AiMessage.toolExecutionRequests()
 * 3. 短期记忆   —— ChatMemory（MessageWindowChatMemory + ChatMemoryStore桥接领域端口）
 * 4. RAG增强    —— KnowledgeBase检索知识注入SystemMessage
 * 5. 长期记忆   —— UserMemory用户画像个性化
 * 6. 监控       —— Langfuse记录真实TokenUsage（来自Response.tokenUsage()）
 *
 * 架构设计：
 * - 实现Domain层定义的AiIntentRecognizer端口
 * - Domain不知道LangChain4j/Qwen/RAG/Memory/Langfuse的存在
 * - 切换模型或框架不影响领域代码
 */
@Component
public class LangChain4jIntentRecognizer implements AiIntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jIntentRecognizer.class);

    /** 短期记忆滑动窗口大小 */
    private static final int MEMORY_WINDOW = 20;

    /**
     * 通用政策/规则咨询信号：规则类名词（规则/政策/时效/运费/发票/会员…）
     * 或平台级主体（你们/平台/商家/贵…）。命中即视为"问平台通用规则"，而非查某笔订单。
     */
    private static final Pattern POLICY_HINT = Pattern.compile(
            "规则|政策|规定|标准|流程|时效|运费|邮费|包邮|收费|费用|发票|会员|权益|积分|折扣|分期|"
                    + "支付方式|配送范围|你们|您们|平台|商家|贵司|贵公司|一般|通常|介绍|说明");

    /**
     * 具体订单指向：物主/指示代词（我的/这个/该…）紧跟订单/快递/物流等名词，
     * 表示用户问的是自己某一笔订单，而非平台通用规则。
     */
    private static final Pattern SPECIFIC_ORDER_HINT = Pattern.compile(
            "(我的|我这个|这个|那个|我那|该|本人)(订单|快递|包裹|物流|货|商品|单子)");

    private final ChatLanguageModel chatModel;
    private final LangChain4jToolAdapter toolAdapter;
    private final QwenProperties properties;
    private final PromptManager promptManager;
    private final KnowledgeBase knowledgeBase;
    private final ConversationMemoryChatMemoryStore chatMemoryStore;
    private final UserMemory userMemory;
    private final LangfuseClient langfuseClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.qwen.mock-enabled:true}")
    private boolean mockEnabled;

    public LangChain4jIntentRecognizer(ChatLanguageModel chatModel,
                                        LangChain4jToolAdapter toolAdapter,
                                        QwenProperties properties,
                                        PromptManager promptManager,
                                        KnowledgeBase knowledgeBase,
                                        ConversationMemoryChatMemoryStore chatMemoryStore,
                                        UserMemory userMemory,
                                        LangfuseClient langfuseClient,
                                        ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolAdapter = toolAdapter;
        this.properties = properties;
        this.promptManager = promptManager;
        this.knowledgeBase = knowledgeBase;
        this.chatMemoryStore = chatMemoryStore;
        this.userMemory = userMemory;
        this.langfuseClient = langfuseClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info("LangChain4jIntentRecognizer初始化: mockEnabled={}, model={}, tools={}",
                mockEnabled, properties.getModel(), toolAdapter.specifications().size());
    }

    // ==================== 简单模式（无上下文） ====================

    @Override
    @Retryable(retryFor = Exception.class, maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public IntentRecognitionResult recognize(String content) {
        if (mockEnabled) {
            return mockRecognize(content);
        }
        return doRecognize(content);
    }

    @Override
    public String generateResponse(String content, TicketIntent intent, List<String> context) {
        if (mockEnabled) {
            return mockGenerateResponse(content, intent, context);
        }
        return doGenerateResponse(content, intent, context);
    }

    // ==================== 增强模式（记忆 + RAG + 监控） ====================

    @Override
    @Retryable(retryFor = Exception.class, maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public IntentRecognitionResult recognize(String content, ProcessContext ctx) {
        if (ctx == null) {
            return recognize(content);
        }
        if (mockEnabled) {
            // Mock模式同样写入"用户轮"，与真实模式保持一致的多轮记忆行为
            recordUserTurn(ctx.sessionId(), content);
            return applySlotClarification(mockRecognize(content), content);
        }

        String traceId = langfuseClient.createTrace(
                "intent_recognition", ctx.sessionId(),
                Map.of("customerId", ctx.customerId(), "inputLength", content.length()));
        long startTime = System.currentTimeMillis();

        try {
            // 1. LangChain4j短期记忆：写入用户消息（滑动窗口）
            ChatMemory memory = chatMemoryStore.windowMemory(ctx.sessionId(), MEMORY_WINDOW);
            memory.add(UserMessage.from(content));

            // 2. RAG：检索相关知识
            List<KnowledgeChunk> knowledge = knowledgeBase.retrieve(content, 3);
            langfuseClient.createEvent(traceId, "rag_retrieval",
                    Map.of("query", content, "chunksFound", knowledge.size()));

            // 3. 长期记忆：用户画像
            UserMemoryData userProfile = userMemory.get(ctx.customerId());
            langfuseClient.createEvent(traceId, "user_memory_loaded",
                    Map.of("userId", ctx.customerId(),
                            "totalInteractions", userProfile.totalInteractions()));

            // 4. 构建消息：动态SystemMessage（融合Prompt模板+RAG+画像） + 记忆历史
            PromptTemplate template = promptManager.getTemplate("intent_recognition");
            String basePrompt = template.render(Map.of("userInput", content));
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(
                    buildEnhancedSystemPrompt(basePrompt, knowledge, userProfile)));
            messages.addAll(memory.messages());

            // 5. 调用LangChain4j ChatLanguageModel（带工具规格 → 真实Function Calling）
            Response<AiMessage> response = chatModel.generate(messages, toolAdapter.specifications());
            AiMessage aiMessage = response.content();

            // 6. 解析结果：工具调用 或 文本意图（传入服务端客户身份，供文本分支合成 list_orders）
            IntentRecognitionResult result = parseAiMessage(aiMessage, content, ctx.customerId());

            // 7. 短期记忆此处只保留"用户轮"（步骤1已写入UserMessage）。
            //    不写入aiMessage：它是意图分析结果(JSON/工具调用，text常为null)，并非面向
            //    用户的自然语言回复，写进去会污染对话历史。真正的"助手轮"由 generateResponse 写入。

            // 8. 更新长期记忆（用户画像）
            updateUserMemory(ctx.customerId(), result.intent());

            // 9. Langfuse记录真实TokenUsage
            long latency = System.currentTimeMillis() - startTime;
            TokenUsage usage = response.tokenUsage();
            langfuseClient.createSpan(traceId, "intent_recognition",
                    properties.getModel(), content, result.reasoning(),
                    latency, inputTokens(usage, content), outputTokens(usage, result.reasoning()));

            return applySlotClarification(result, content);
        } catch (Exception e) {
            langfuseClient.createEvent(traceId, "recognition_error",
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    @Override
    public String generateResponse(String content, TicketIntent intent,
                                    List<String> context, ProcessContext ctx) {
        if (ctx == null) {
            return generateResponse(content, intent, context);
        }
        if (mockEnabled) {
            String reply = mockGenerateResponse(content, intent, context);
            recordAssistantTurn(ctx.sessionId(), reply);
            return reply;
        }

        String traceId = langfuseClient.createTrace(
                "response_generation", ctx.sessionId(),
                Map.of("intent", intent.name()));
        long startTime = System.currentTimeMillis();

        try {
            // RAG增强：检索知识作为回复参考
            List<KnowledgeChunk> knowledge = knowledgeBase.retrieve(content, 3);
            String knowledgeContext = knowledge.isEmpty() ? "无相关知识" :
                    knowledge.stream().map(KnowledgeChunk::content)
                            .collect(Collectors.joining("\n---\n"));
            String toolContextStr = context.isEmpty() ? "无" : String.join("\n", context);

            PromptTemplate ragTemplate = promptManager.getTemplate("rag_enhanced");
            String userPrompt = ragTemplate.render(Map.of(
                    "knowledgeContext", knowledgeContext,
                    "userInput", content));

            List<ChatMessage> messages = List.of(
                    SystemMessage.from("你是一个专业、友好的智能客服助手。优先使用知识库中的准确信息回复用户。"),
                    UserMessage.from(String.format("""
                            %s

                            ## 查询结果
                            %s

                            ## 识别意图
                            %s
                            """, userPrompt, toolContextStr, intent.getDescription()))
            );

            Response<AiMessage> response = chatModel.generate(messages);
            String text = response.content().text();
            String reply = text != null ? text : "处理完成";

            // 把面向用户的自然语言回复写入短期记忆，作为本轮"助手轮"，
            // 让后续轮次能看到AI说过什么（多轮上下文连贯、query改写更准确的关键）
            recordAssistantTurn(ctx.sessionId(), reply);

            long latency = System.currentTimeMillis() - startTime;
            TokenUsage usage = response.tokenUsage();
            langfuseClient.createSpan(traceId, "response_generation",
                    properties.getModel(), content, reply,
                    latency, inputTokens(usage, content), outputTokens(usage, reply));

            return reply;
        } catch (Exception e) {
            langfuseClient.createEvent(traceId, "generation_error",
                    Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    // ==================== 会话query改写（多轮对话） ====================

    @Override
    public String rewriteQuery(String rawQuery, ProcessContext ctx) {
        if (ctx == null || mockEnabled || rawQuery == null || rawQuery.isBlank()) {
            return rawQuery;
        }
        try {
            ChatMemory memory = chatMemoryStore.windowMemory(ctx.sessionId(), MEMORY_WINDOW);
            List<ChatMessage> history = memory.messages();
            // 首轮（无历史）无需改写
            if (history == null || history.isEmpty()) {
                return rawQuery;
            }
            StringBuilder histText = new StringBuilder();
            for (ChatMessage m : history) {
                String role = (m instanceof UserMessage) ? "用户" : "客服";
                String text = m.text();
                if (text != null && !text.isBlank()) {
                    histText.append(role).append(": ").append(text).append("\n");
                }
            }
            PromptTemplate template = promptManager.getTemplate("query_rewrite");
            String prompt = template.render(Map.of(
                    "history", histText.toString(),
                    "userInput", rawQuery));

            Response<AiMessage> resp = chatModel.generate(List.of(
                    SystemMessage.from("你是query改写器，只输出改写后的独立query本身。"),
                    UserMessage.from(prompt)));
            String rewritten = resp.content() != null ? resp.content().text() : null;
            if (rewritten != null && !rewritten.isBlank()) {
                String r = rewritten.trim();
                // 长度保护：改写不应显著膨胀，防止模型输出多余内容
                if (r.length() <= rawQuery.length() * 4 + 80) {
                    log.debug("query改写: [{}] -> [{}]", rawQuery, r);
                    return r;
                }
            }
        } catch (Exception e) {
            log.warn("query改写失败，使用原始query: {}", e.getMessage());
        }
        return rawQuery;
    }

    // ==================== 基础AI调用（无上下文增强） ====================

    private IntentRecognitionResult doRecognize(String content) {
        PromptTemplate template = promptManager.getTemplate("intent_recognition");
        String systemPrompt = template.render(Map.of("userInput", content));

        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(content)
        );
        Response<AiMessage> response = chatModel.generate(messages, toolAdapter.specifications());
        return parseAiMessage(response.content(), content, null);
    }

    private String doGenerateResponse(String content, TicketIntent intent, List<String> context) {
        String contextStr = context.isEmpty() ? "无" : String.join("\n", context);
        PromptTemplate template = promptManager.getTemplate("response_generation");
        String userPrompt = template.render(Map.of(
                "userInput", content,
                "intent", intent.getDescription(),
                "context", contextStr));

        List<ChatMessage> messages = List.of(
                SystemMessage.from("你是一个智能客服助手。根据识别到的用户意图和查询结果，生成友好、专业的回复。回复要简洁明了。"),
                UserMessage.from(userPrompt));

        Response<AiMessage> response = chatModel.generate(messages);
        String text = response.content().text();
        return text != null ? text : "处理完成";
    }

    // ==================== LangChain4j响应解析 ====================

    /**
     * 解析AiMessage：优先处理Function Calling的工具请求，否则按文本解析意图
     *
     * @param content    用户原始诉求，用于在"文本JSON"分支兜底提取订单号/合成工具调用
     * @param customerId 服务端上下文客户身份，用于合成 list_orders 等按客户查询的工具（可为null）
     */
    private IntentRecognitionResult parseAiMessage(AiMessage aiMessage, String content, String customerId) {
        if (aiMessage == null) {
            return new IntentRecognitionResult(TicketIntent.UNKNOWN, 0.0, "AI返回为空", List.of());
        }
        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolCall> calls = aiMessage.toolExecutionRequests().stream()
                    .map(this::toDomainToolCall)
                    .collect(Collectors.toList());
            TicketIntent intent = inferIntentFromToolCalls(calls);
            String reasoning = "通过Function Calling调用工具: " +
                    calls.stream().map(ToolCall::toolName).collect(Collectors.joining(", "));
            return new IntentRecognitionResult(intent, 0.9, reasoning, calls);
        }
        String text = aiMessage.text();
        return parseTextResponse(text, content, customerId);
    }

    private ToolCall toDomainToolCall(ToolExecutionRequest request) {
        return new ToolCall(request.name(), parseJsonArgs(request.arguments()));
    }

    private TicketIntent inferIntentFromToolCalls(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            TicketIntent intent = switch (call.toolName()) {
                case "query_order" -> TicketIntent.ORDER_QUERY;
                case "list_orders" -> TicketIntent.ORDER_QUERY;
                case "query_logistics" -> TicketIntent.LOGISTICS;
                case "create_after_sale" -> TicketIntent.AFTER_SALE;
                case "process_refund" -> TicketIntent.AFTER_SALE;
                case "query_after_sale" -> TicketIntent.AFTER_SALE;
                case "cancel_order" -> TicketIntent.CANCEL_ORDER;
                default -> TicketIntent.UNKNOWN; // run_code / pay_order 等不决定意图
            };
            if (intent != TicketIntent.UNKNOWN) {
                return intent;
            }
        }
        return TicketIntent.UNKNOWN;
    }

    /**
     * 槽位澄清检查（问题补充）：订单/物流/售后/取消类意图必须有订单号，
     * 若工具调用参数与用户原文中都找不到订单号，则转为"需要澄清"，
     * 由编排层向用户追问订单号，本轮挂起。
     */
    private IntentRecognitionResult applySlotClarification(IntentRecognitionResult result, String content) {
        if (result == null || result.needsClarification()) {
            return result;
        }
        // 已合成出可执行工具（如"我有哪些订单"→list_orders，本就无需订单号），不再降级为澄清
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            return result;
        }
        TicketIntent intent = result.intent();
        boolean needsOrder = intent == TicketIntent.ORDER_QUERY
                || intent == TicketIntent.LOGISTICS
                || intent == TicketIntent.AFTER_SALE
                || intent == TicketIntent.CANCEL_ORDER;
        if (!needsOrder) {
            return result;
        }
        // 通用政策/规则咨询（如"物流规则""退货政策"）不指向具体订单，应走知识库作答，不追问订单号
        if (isGeneralPolicyQuestion(content)) {
            return result;
        }
        if (hasOrderNo(result.toolCalls(), content)) {
            return result;
        }
        String question = "请问您指的是哪个订单呢？麻烦提供一下订单号，我好帮您处理～";
        return new IntentRecognitionResult(
                TicketIntent.CLARIFY, result.confidence(),
                "缺少必填槽位 orderNo，需向用户澄清", result.toolCalls(),
                true, question, List.of("orderNo"));
    }

    private boolean hasOrderNo(List<ToolCall> calls, String content) {
        if (calls != null) {
            for (ToolCall c : calls) {
                if (c.arguments() != null) {
                    Object o = c.arguments().get("orderNo");
                    if (o != null && !String.valueOf(o).isBlank()
                            && !"UNKNOWN".equalsIgnoreCase(String.valueOf(o))) {
                        return true;
                    }
                }
            }
        }
        if (content != null) {
            // 订单号形态：ORD开头 或 连续4位以上数字
            return Pattern.compile("(?i)ORD[-_]?\\w+").matcher(content).find()
                    || Pattern.compile("\\d{4,}").matcher(content).find();
        }
        return false;
    }

    /**
     * 是否为"通用政策/规则咨询"——询问平台通用规则（物流规则、退货政策、运费、时效、
     * 支付方式、发票、会员权益等），不指向用户某笔具体订单。这类问题应由知识库(RAG)作答，
     * 而不是追问订单号。
     *
     * 判别：命中政策/规则类强信号，且不含"我的/这个+订单/快递/物流"这类具体订单指向。
     * 例："你们平台的物流规则是什么样的"→true；"我的快递到哪了"→false（具体订单，需订单号）。
     */
    static boolean isGeneralPolicyQuestion(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return POLICY_HINT.matcher(content).find()
                && !SPECIFIC_ORDER_HINT.matcher(content).find();
    }

    /**
     * 文本分支兜底解析。
     *
     * 背景：意图识别Prompt要求模型输出 {"intent","reasoning","orderNo"} 文本JSON，
     * 这与 Function Calling 存在竞争——模型常常"描述"出取消意图却不调用 cancel_order，
     * 导致 toolCalls 为空、编排层误判为"无工具→查知识库"，敏感动作永远不触发。
     * 修复：文本分支也依据 intent + 订单号合成等价工具调用，使链路与 Function Calling 一致。
     */
    private IntentRecognitionResult parseTextResponse(String text, String content, String customerId) {
        TicketIntent intent = extractIntentFromText(text);
        String orderNo = extractOrderNoFromText(text, content);
        List<ToolCall> calls = synthesizeToolCalls(intent, orderNo, content, customerId);
        return new IntentRecognitionResult(intent, 0.8, text, calls);
    }

    /**
     * 提取订单号：优先取模型文本JSON里的 orderNo 字段，其次取用户原文中的订单号形态。
     * 提取不到返回 null（交由槽位澄清追问），绝不臆造默认订单号。
     */
    static String extractOrderNoFromText(String text, String content) {
        String fromJson = firstGroup(Pattern.compile("\"orderNo\"\\s*:\\s*\"?([A-Za-z0-9_-]+)\"?"), text);
        if (fromJson != null && !fromJson.isBlank() && !"null".equalsIgnoreCase(fromJson)) {
            return fromJson;
        }
        String ord = firstGroup(Pattern.compile("(?i)(ORD[-_0-9A-Za-z]+)"), content);
        if (ord != null) {
            return ord;
        }
        return firstGroup(Pattern.compile("(\\d{4,})"), content);
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        var m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 意图 → 工具调用 的合成映射（与 Function Calling 路径等价）。
     *
     * ORDER_QUERY 有两种形态：
     * - 带订单号 → query_order(orderNo)，查询单个订单
     * - 无订单号但有客户身份 → list_orders(customerId)，回应"我有哪些订单"这类列表诉求
     * 其余需订单号的意图（取消/物流/售后）在 orderNo 缺失时返回空列表，
     * 由 {@link #applySlotClarification} 触发澄清。
     */
    static List<ToolCall> synthesizeToolCalls(TicketIntent intent, String orderNo, String reason, String customerId) {
        if (intent == null) {
            return List.of();
        }
        boolean hasOrder = orderNo != null && !orderNo.isBlank();
        boolean withReason = reason != null && !reason.isBlank();
        boolean hasCustomer = customerId != null && !customerId.isBlank();
        return switch (intent) {
            case ORDER_QUERY -> {
                if (hasOrder) {
                    Map<String, Object> a = new HashMap<>();
                    a.put("orderNo", orderNo);
                    yield List.of(new ToolCall("query_order", a));
                }
                if (hasCustomer) {
                    Map<String, Object> a = new HashMap<>();
                    a.put("customerId", customerId);
                    yield List.of(new ToolCall("list_orders", a));
                }
                yield List.of();
            }
            case CANCEL_ORDER -> {
                if (!hasOrder) {
                    yield List.of();
                }
                Map<String, Object> a = new HashMap<>();
                a.put("orderNo", orderNo);
                if (withReason) {
                    a.put("reason", reason);
                }
                yield List.of(new ToolCall("cancel_order", a));
            }
            case LOGISTICS -> {
                if (!hasOrder) {
                    yield List.of();
                }
                Map<String, Object> a = new HashMap<>();
                a.put("orderNo", orderNo);
                yield List.of(new ToolCall("query_logistics", a));
            }
            case AFTER_SALE -> {
                if (!hasOrder) {
                    yield List.of();
                }
                Map<String, Object> q = new HashMap<>();
                q.put("orderNo", orderNo);
                Map<String, Object> c = new HashMap<>();
                c.put("orderNo", orderNo);
                if (withReason) {
                    c.put("reason", reason);
                }
                yield List.of(new ToolCall("query_order", q), new ToolCall("create_after_sale", c));
            }
            default -> List.of();
        };
    }

    /**
     * 构建增强的系统提示（融合RAG知识和用户画像）
     */
    private String buildEnhancedSystemPrompt(String basePrompt,
                                              List<KnowledgeChunk> knowledge,
                                              UserMemoryData userProfile) {
        StringBuilder sb = new StringBuilder(basePrompt);
        if (!knowledge.isEmpty()) {
            sb.append("\n\n## 相关知识参考\n");
            for (KnowledgeChunk chunk : knowledge) {
                sb.append("- ").append(chunk.content()).append("\n");
            }
        }
        if (userProfile.totalInteractions() > 0) {
            sb.append("\n## 用户画像\n");
            sb.append("- 历史交互次数: ").append(userProfile.totalInteractions()).append("\n");
            if (userProfile.commonIntent() != null) {
                sb.append("- 常见咨询类型: ").append(userProfile.commonIntent()).append("\n");
            }
            sb.append("- 偏好语言: ").append(userProfile.preferredLanguage()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 记忆管理 ====================

    /**
     * 写入"用户轮"到短期记忆（mock与真实模式共用）。
     * 失败不阻断主流程：记忆是增强项，不能因它让请求失败。
     */
    private void recordUserTurn(String sessionId, String text) {
        try {
            ChatMemory memory = chatMemoryStore.windowMemory(sessionId, MEMORY_WINDOW);
            memory.add(UserMessage.from(text));
        } catch (Exception e) {
            log.warn("写入用户轮短期记忆失败，已忽略: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    /**
     * 写入"助手轮"到短期记忆——面向用户的自然语言回复。
     * 这样后续轮次能看到AI说过什么，保证多轮上下文连贯、query改写更准确。
     */
    private void recordAssistantTurn(String sessionId, String text) {
        try {
            ChatMemory memory = chatMemoryStore.windowMemory(sessionId, MEMORY_WINDOW);
            memory.add(AiMessage.from(text));
        } catch (Exception e) {
            log.warn("写入助手轮短期记忆失败，已忽略: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private void updateUserMemory(String customerId, TicketIntent intent) {
        try {
            UserMemoryData current = userMemory.get(customerId);
            UserMemoryData updated = new UserMemoryData(
                    customerId,
                    current.preferredLanguage(),
                    intent.name(),
                    current.preferences(),
                    current.lastSummary(),
                    current.totalInteractions() + 1,
                    System.currentTimeMillis());
            userMemory.save(customerId, updated);
        } catch (Exception e) {
            log.warn("更新用户记忆失败: customerId={}", customerId, e);
        }
    }

    // ==================== 工具方法 ====================

    private int inputTokens(TokenUsage usage, String fallbackText) {
        if (usage != null && usage.inputTokenCount() != null) {
            return usage.inputTokenCount();
        }
        return estimateTokens(fallbackText);
    }

    private int outputTokens(TokenUsage usage, String fallbackText) {
        if (usage != null && usage.outputTokenCount() != null) {
            return usage.outputTokenCount();
        }
        return estimateTokens(fallbackText);
    }

    private String extractOrderNo(String content) {
        var matcher = Pattern.compile("\\d{4,}").matcher(content);
        return matcher.find() ? matcher.group() : "10086";
    }

    private TicketIntent extractIntentFromText(String text) {
        if (text == null) return TicketIntent.UNKNOWN;
        if (text.contains("CANCEL_ORDER")) return TicketIntent.CANCEL_ORDER;
        if (text.contains("ORDER_QUERY")) return TicketIntent.ORDER_QUERY;
        if (text.contains("LOGISTICS")) return TicketIntent.LOGISTICS;
        if (text.contains("AFTER_SALE")) return TicketIntent.AFTER_SALE;
        return TicketIntent.UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonArgs(String json) {
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

    private int estimateTokens(String text) {
        if (text == null) return 0;
        return Math.max(1, text.length() / 2);
    }

    // ==================== Mock模式（开发/演示用） ====================

    private IntentRecognitionResult mockRecognize(String content) {
        log.info("[Mock模式] 识别意图: {}", content);
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        String orderNo = extractOrderNo(content);
        TicketIntent intent;
        String reasoning;

        if (content.contains("物流") || content.contains("快递") ||
                content.contains("发货") || content.contains("配送") ||
                content.contains("到哪了") || content.contains("还没发") ||
                content.toLowerCase().contains("logistics") ||
                content.toLowerCase().contains("shipping") ||
                content.toLowerCase().contains("delivery")) {
            intent = TicketIntent.LOGISTICS;
            reasoning = "用户询问物流/发货相关信息";
        } else if (content.contains("退") || content.contains("换") ||
                content.contains("售后") || content.contains("维修") ||
                content.contains("投诉") ||
                content.toLowerCase().contains("refund") ||
                content.toLowerCase().contains("return") ||
                content.toLowerCase().contains("after-sale")) {
            intent = TicketIntent.AFTER_SALE;
            reasoning = "用户提出售后/退换货需求";
        } else if (content.contains("订单") || content.contains("查询") ||
                content.contains("状态") ||
                content.toLowerCase().contains("order") ||
                content.toLowerCase().contains("status") ||
                content.toLowerCase().contains("query")) {
            intent = TicketIntent.ORDER_QUERY;
            reasoning = "用户查询订单信息";
        } else {
            intent = TicketIntent.UNKNOWN;
            reasoning = "无法识别用户意图";
        }

        List<ToolCall> toolCalls = switch (intent) {
            case LOGISTICS -> List.of(new ToolCall("query_logistics", Map.of("orderNo", orderNo)));
            case AFTER_SALE -> List.of(
                    new ToolCall("query_order", Map.of("orderNo", orderNo)),
                    new ToolCall("create_after_sale", Map.of("orderNo", orderNo, "reason", content)));
            case ORDER_QUERY -> List.of(new ToolCall("query_order", Map.of("orderNo", orderNo)));
            default -> List.of();
        };
        return new IntentRecognitionResult(intent, 0.85, reasoning, toolCalls);
    }

    private String mockGenerateResponse(String content, TicketIntent intent, List<String> context) {
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return switch (intent) {
            case LOGISTICS -> "您好，已为您查询到物流信息。您的订单正在运输中，预计明天送达。如有其他问题请随时联系我。";
            case AFTER_SALE -> "您好，已为您创建售后工单。我们的客服会在24小时内与您联系处理退款事宜，请保持手机畅通。";
            case ORDER_QUERY -> "您好，已为您查询到订单信息。您的订单状态正常，如有其他疑问请随时联系我。";
            case CANCEL_ORDER -> "您好，取消订单为不可逆操作，请您确认后我们再为您处理。";
            case CLARIFY -> "您好，请问您指的是哪个订单呢？麻烦提供一下订单号。";
            case UNKNOWN -> "您好，我已收到您的问题，正在为您转接人工客服。请稍等片刻，我们会尽快为您处理。";
        };
    }
}
