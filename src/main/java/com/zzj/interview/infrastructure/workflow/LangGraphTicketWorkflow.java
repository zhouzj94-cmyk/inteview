package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.gateway.AiIntentRecognizer;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.IntentRecognitionResult;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.ProcessContext;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.ToolCall;
import com.zzj.interview.domain.memory.UserMemory;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.rag.KnowledgeBase;
import com.zzj.interview.domain.rag.KnowledgeChunk;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.tool.BusinessActionExecutor;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 LangGraph4j 的 Plan-and-Execute 工单处理图（真实编排器）
 *
 * 设计理念：AI 是外部能力（意图识别/改写/回复生成），本图负责"规划—执行—验证—回复"的业务编排。
 * 规划与执行分离，图才能显式决定"这一步查知识库还是调工具"，并对每一步做结果验证。
 *
 * 图结构（受控条件边）：
 * <pre>
 *  START → rewrite → plan ──R_step──┬─ needsClarification → clarify → END(挂起等补充)
 *                                   ├─ step=RETRIEVE → retrieve ─┐
 *                                   ├─ step=TOOL 敏感且未批准 → confirm(中断点) ─┤
 *                                   ├─ step=TOOL → execute_tool ─┤
 *                                   └─ 游标耗尽 → verify          │
 *      R_step(推进游标后复用) ← retrieve/execute_tool/confirm ─────┘
 *      verify ──R_verify──┬─ 通过/用户拒绝 → respond → memory → END
 *                         ├─ 失败且 replanCount<MAX → plan(带反馈重规划,清空结果重试)
 *                         └─ 失败且重规划耗尽 → respond(诚实反馈失败) → memory → END
 * </pre>
 *
 * 人机确认（S1，纯 LangGraph 中断+检查点）：
 * - compile 时 {@code interruptBefore("confirm")} + {@link MemorySaver} 检查点；
 * - threadId = conversationId，敏感动作前图停在 confirm 断点；
 * - 确认接口对同一张图 {@code updateState(注入 approved)} + {@code stream(null,cfg)} 续跑。
 *
 * 循环安全：MAX_REPLAN 限制重规划次数 + recursionLimit 兜底，杜绝死循环。
 */
@Service
public class LangGraphTicketWorkflow {

    private static final Logger log = LoggerFactory.getLogger(LangGraphTicketWorkflow.class);

    /** 验证失败后最多重规划（重试）次数，防止死循环 */
    private static final int MAX_REPLAN = 1;
    /** 知识库检索条数 */
    private static final int RETRIEVE_TOP_K = 3;
    /** 递归上限兜底（正常流程远低于此值） */
    private static final int RECURSION_LIMIT = 30;

    // 节点名
    private static final String N_REWRITE = "rewrite";
    private static final String N_PLAN = "plan";
    private static final String N_RETRIEVE = "retrieve";
    private static final String N_EXECUTE = "execute_tool";
    private static final String N_CONFIRM = "confirm";
    private static final String N_VERIFY = "verify";
    private static final String N_RESPOND = "respond";
    private static final String N_MEMORY = "memory";
    private static final String N_CLARIFY = "clarify";

    // 路由键
    private static final String R_CLARIFY = "clarify";
    private static final String R_RETRIEVE = "retrieve";
    private static final String R_CONFIRM = "confirm";
    private static final String R_EXECUTE = "execute";
    private static final String R_VERIFY = "verify";
    private static final String R_RESPOND = "respond";
    private static final String R_REPLAN = "replan";

    private final AiIntentRecognizer ai;
    private final KnowledgeBase knowledgeBase;
    private final BusinessActionExecutor actionExecutor;
    private final ActionIntentResolver intentResolver;
    private final UserMemory userMemory;

    private final CompiledGraph<TicketState> graph;

    public LangGraphTicketWorkflow(AiIntentRecognizer ai,
                                   KnowledgeBase knowledgeBase,
                                   BusinessActionExecutor actionExecutor,
                                   ActionIntentResolver intentResolver,
                                   UserMemory userMemory) {
        this.ai = ai;
        this.knowledgeBase = knowledgeBase;
        this.actionExecutor = actionExecutor;
        this.intentResolver = intentResolver;
        this.userMemory = userMemory;
        try {
            this.graph = buildGraph();
            log.info("Plan-and-Execute 工单图初始化成功（中断点=confirm，检查点=MemorySaver）");
        } catch (Exception e) {
            log.error("Plan-and-Execute 工单图初始化失败：{}", e.getMessage(), e);
            throw new RuntimeException("LangGraph 工单图初始化失败", e);
        }
    }

    /** 暴露已编译的图，供 Runner 驱动 stream/getState/updateState */
    public CompiledGraph<TicketState> getGraph() {
        return graph;
    }

    public String confirmNodeName() {
        return N_CONFIRM;
    }

    private CompiledGraph<TicketState> buildGraph() throws Exception {
        StateGraph<TicketState> sg = new StateGraph<>(TicketState::new)
                .addNode(N_REWRITE, AsyncNodeAction.node_async(this::rewrite))
                .addNode(N_PLAN, AsyncNodeAction.node_async(this::plan))
                .addNode(N_RETRIEVE, AsyncNodeAction.node_async(this::retrieve))
                .addNode(N_EXECUTE, AsyncNodeAction.node_async(this::executeTool))
                .addNode(N_CONFIRM, AsyncNodeAction.node_async(this::confirm))
                .addNode(N_VERIFY, AsyncNodeAction.node_async(this::verify))
                .addNode(N_RESPOND, AsyncNodeAction.node_async(this::respond))
                .addNode(N_MEMORY, AsyncNodeAction.node_async(this::memory))
                .addNode(N_CLARIFY, AsyncNodeAction.node_async(this::clarify));

        Map<String, String> stepRoutes = Map.of(
                R_CLARIFY, N_CLARIFY,
                R_RETRIEVE, N_RETRIEVE,
                R_CONFIRM, N_CONFIRM,
                R_EXECUTE, N_EXECUTE,
                R_VERIFY, N_VERIFY);
        AsyncEdgeAction stepRouter = AsyncEdgeAction.edge_async(this::stepRoute);

        sg.addEdge(StateGraph.START, N_REWRITE);
        sg.addEdge(N_REWRITE, N_PLAN);
        // plan / retrieve / execute_tool / confirm 执行后都回到同一个游标路由器
        sg.addConditionalEdges(N_PLAN, stepRouter, stepRoutes);
        sg.addConditionalEdges(N_RETRIEVE, stepRouter, stepRoutes);
        sg.addConditionalEdges(N_EXECUTE, stepRouter, stepRoutes);
        sg.addConditionalEdges(N_CONFIRM, stepRouter, stepRoutes);
        sg.addConditionalEdges(N_VERIFY, AsyncEdgeAction.edge_async(this::verifyRoute),
                Map.of(R_RESPOND, N_RESPOND, R_REPLAN, N_PLAN));
        sg.addEdge(N_RESPOND, N_MEMORY);
        sg.addEdge(N_MEMORY, StateGraph.END);
        sg.addEdge(N_CLARIFY, StateGraph.END);

        return sg.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore(N_CONFIRM)
                .recursionLimit(RECURSION_LIMIT)
                .build());
    }

    // ============ 路由（条件边） ============

    /** 游标路由：澄清 / 检索 / 确认 / 执行 / 验证 */
    private String stepRoute(TicketState state) {
        if (state.needsClarification()) {
            return R_CLARIFY;
        }
        PlanStep step = state.currentStep();
        if (step == null) {
            return R_VERIFY;                 // 游标耗尽 → 验证
        }
        if (step.isRetrieve()) {
            return R_RETRIEVE;
        }
        if (step.isTool()) {
            boolean gated = actionExecutor.requiresConfirmation(step.toolName());
            if (gated && !state.approved()) {
                return R_CONFIRM;            // 敏感且未批准 → 确认（中断点）
            }
            return R_EXECUTE;
        }
        return R_VERIFY;
    }

    /** 验证路由：通过/拒绝→回复；失败且可重规划→plan；耗尽→回复（诚实失败） */
    private String verifyRoute(TicketState state) {
        if (state.verified()) {
            return R_RESPOND;
        }
        if (state.replanRequested()) {
            return R_REPLAN;
        }
        return R_RESPOND;
    }

    // ============ 节点动作 ============

    private Map<String, Object> rewrite(TicketState state) {
        ProcessContext ctx = ctx(state);
        String rewritten;
        try {
            rewritten = ai.rewriteQuery(state.content(), ctx);
        } catch (Exception e) {
            log.warn("[rewrite] query改写失败，沿用原文: {}", e.getMessage());
            rewritten = state.content();
        }
        if (rewritten == null || rewritten.isBlank()) {
            rewritten = state.content();
        }
        Map<String, Object> u = new HashMap<>();
        u.put("rewrittenQuery", rewritten);
        u.put("steps", trace(state, rewritten.equals(state.content())
                ? "rewrite: 无需改写"
                : "rewrite: " + state.content() + " → " + rewritten));
        return u;
    }

    private Map<String, Object> plan(TicketState state) {
        if (state.replanRequested()) {
            return replan(state);
        }
        ProcessContext ctx = ctx(state);
        IntentRecognitionResult result;
        try {
            result = ai.recognize(state.rewrittenQuery(), ctx);
        } catch (Exception e) {
            // 意图识别失败属外部AI异常：抛出，由 Runner 上层降级为人工处理
            log.error("[plan] 意图识别失败，交由上层降级: {}", e.getMessage());
            throw new RuntimeException("意图识别失败: " + e.getMessage(), e);
        }

        List<ToolCall> calls = result.toolCalls() != null ? result.toolCalls() : List.of();

        // 选项C：澄清由"所选动作的必填参数"驱动（AI 已标记澄清 或 动作缺必填参数）
        List<String> missing = new ArrayList<>();
        for (ToolCall c : calls) {
            if (!actionExecutor.isKnown(c.toolName())) {
                continue;
            }
            for (String m : actionExecutor.missingRequiredParams(c.toolName(), c.arguments())) {
                if (!missing.contains(m)) {
                    missing.add(m);
                }
            }
        }
        boolean needClarify = result.needsClarification() || !missing.isEmpty();

        Map<String, Object> u = new HashMap<>();
        TicketIntent intent = intentResolver.resolve(toolNames(calls), result.intent());
        u.put("intent", intent.name());
        u.put("confidence", result.confidence());
        u.put("reasoning", result.reasoning() != null ? result.reasoning() : "");

        if (needClarify) {
            List<String> slots = !missing.isEmpty() ? missing
                    : (result.missingSlots() != null ? result.missingSlots() : List.of());
            String q = (result.clarificationQuestion() != null && !result.clarificationQuestion().isBlank())
                    ? result.clarificationQuestion()
                    : buildClarifyQuestion(slots);
            u.put("needsClarification", true);
            u.put("clarificationQuestion", q);
            u.put("missingSlots", slots);
            u.put("plan", List.of());
            u.put("planCursor", 0);
            u.put("steps", trace(state, "plan: 需澄清 slots=" + slots));
            return u;
        }

        // 构建规划：有工具→逐个 TOOL 步骤；无工具→RETRIEVE（查知识库作答）
        List<PlanStep> plan = new ArrayList<>();
        if (calls.isEmpty()) {
            plan.add(PlanStep.retrieve("无可执行工具，检索知识库作答"));
        } else {
            for (ToolCall c : calls) {
                // customerId 是服务端上下文身份，权威注入并覆盖模型值，防止越权访问他人订单
                Map<String, Object> args = actionExecutor.injectCustomerId(
                        c.toolName(), c.arguments(), state.customerId());
                plan.add(PlanStep.tool(c.toolName(), args));
            }
        }

        // 预置首个敏感动作的确认信息（供中断时的 SSE 与落库使用）
        PlanStep gated = firstGated(plan);
        if (gated != null) {
            u.put("needsConfirm", true);
            u.put("pendingTool", gated.toolName());
            u.put("pendingArgs", gated.arguments());
            u.put("confirmPrompt", buildConfirmPrompt(gated));
        }

        u.put("needsClarification", false);
        u.put("plan", plan);
        u.put("planCursor", 0);
        u.put("steps", trace(state, "plan: " + describePlan(plan) + ", intent=" + intent.name()));
        return u;
    }

    /** 重规划：仅重试上一步失败的工具步骤，并清空旧结果使其独立参与下一次验证 */
    private Map<String, Object> replan(TicketState state) {
        Set<String> failed = new HashSet<>();
        for (ToolResult tr : state.toolResults()) {
            if (!tr.isSuccess()) {
                failed.add(tr.getToolName());
            }
        }
        List<PlanStep> retry = new ArrayList<>();
        for (PlanStep s : state.plan()) {
            if (s.isTool() && failed.contains(s.toolName())) {
                retry.add(s);
            }
        }
        Map<String, Object> u = new HashMap<>();
        u.put("plan", retry);
        u.put("planCursor", 0);
        u.put("toolResults", List.of());
        u.put("replanRequested", false);
        u.put("steps", trace(state, "replan(#" + state.replanCount() + "): 重试失败步骤 " + failed));
        return u;
    }

    private Map<String, Object> retrieve(TicketState state) {
        List<String> context = new ArrayList<>(state.retrievedContext());
        int added = 0;
        try {
            List<KnowledgeChunk> chunks = knowledgeBase.retrieve(state.rewrittenQuery(), RETRIEVE_TOP_K);
            for (KnowledgeChunk c : chunks) {
                if (c != null && c.content() != null) {
                    context.add(c.content());
                    added++;
                }
            }
        } catch (Exception e) {
            log.warn("[retrieve] 知识库检索失败，跳过: {}", e.getMessage());
        }
        Map<String, Object> u = new HashMap<>();
        u.put("retrievedContext", context);
        u.put("planCursor", state.planCursor() + 1);
        u.put("steps", trace(state, "retrieve: 命中 " + added + " 条知识"));
        return u;
    }

    private Map<String, Object> executeTool(TicketState state) {
        PlanStep step = state.currentStep();
        Map<String, Object> u = new HashMap<>();
        if (step == null || !step.isTool()) {
            u.put("planCursor", state.planCursor() + 1);
            u.put("steps", trace(state, "execute_tool: 当前步骤非工具，跳过"));
            return u;
        }
        ToolResult r = actionExecutor.execute(step.toolName(), step.arguments());
        List<ToolResult> results = new ArrayList<>(state.toolResults());
        results.add(r);
        u.put("toolResults", results);
        u.put("planCursor", state.planCursor() + 1);
        // 消费掉本次人工批准，避免误批准后续其它敏感步骤
        if (actionExecutor.requiresConfirmation(step.toolName())) {
            u.put("approved", false);
        }
        u.put("steps", trace(state, "execute_tool: " + step.toolName()
                + " → " + (r.isSuccess() ? "成功" : "失败:" + r.getErrorMessage())));
        return u;
    }

    private Map<String, Object> confirm(TicketState state) {
        PlanStep step = state.currentStep();
        String tool = step != null ? step.toolName() : state.pendingTool();
        Map<String, Object> u = new HashMap<>();
        if (state.approved()) {
            // 批准：不推进游标，stepRoute 将路由到 execute_tool 执行该敏感步骤
            u.put("steps", trace(state, "confirm: 用户批准 " + tool + "，继续执行"));
            return u;
        }
        // 拒绝：合成失败结果、推进游标跳过该步、标记 rejected（verify 据此免重试）
        List<ToolResult> results = new ArrayList<>(state.toolResults());
        results.add(ToolResult.failure(tool, "用户拒绝执行该操作，未做任何更改"));
        u.put("toolResults", results);
        u.put("planCursor", state.planCursor() + 1);
        u.put("rejected", true);
        u.put("steps", trace(state, "confirm: 用户拒绝 " + tool + "，跳过执行"));
        return u;
    }

    private Map<String, Object> verify(TicketState state) {
        Map<String, Object> u = new HashMap<>();
        List<String> failures = new ArrayList<>();
        for (ToolResult tr : state.toolResults()) {
            if (!tr.isSuccess()) {
                failures.add(tr.getToolName() + ": "
                        + (tr.getErrorMessage() != null ? tr.getErrorMessage() : "失败"));
            }
        }

        if (state.rejected() || failures.isEmpty()) {
            u.put("verified", true);
            u.put("steps", trace(state, state.rejected()
                    ? "verify: 用户拒绝操作，无需校验执行结果"
                    : "verify: 全部工具执行成功，后置校验通过"));
            return u;
        }

        String feedback = String.join("; ", failures);
        u.put("verified", false);
        u.put("verificationFeedback", feedback);
        if (state.replanCount() < MAX_REPLAN) {
            u.put("replanCount", state.replanCount() + 1);
            u.put("replanRequested", true);
            u.put("steps", trace(state, "verify: 校验失败[" + feedback + "] → 触发重规划#"
                    + (state.replanCount() + 1)));
        } else {
            u.put("steps", trace(state, "verify: 校验失败[" + feedback + "] → 重规划次数耗尽，诚实反馈失败"));
        }
        return u;
    }

    private Map<String, Object> respond(TicketState state) {
        List<String> context = new ArrayList<>(state.retrievedContext());
        context.addAll(VerifiedContextBuilder.build(state.toolResults()));
        if (!state.verificationFeedback().isBlank()) {
            context.add("[结果验证] " + state.verificationFeedback());
        }
        TicketIntent intent = parseIntent(state.intent());
        String response;
        try {
            response = ai.generateResponse(state.rewrittenQuery(), intent, context, ctx(state));
        } catch (Exception e) {
            log.warn("[respond] 回复生成失败，使用兜底文案: {}", e.getMessage());
            response = null;
        }
        if (response == null || response.isBlank()) {
            response = fallbackResponse(state);
        }
        Map<String, Object> u = new HashMap<>();
        u.put("response", response);
        u.put("status", "HANDLED");
        u.put("steps", trace(state, "respond: 生成回复 len=" + response.length()));
        return u;
    }

    private Map<String, Object> memory(TicketState state) {
        try {
            userMemory.recordInteraction(state.customerId(), state.conversationId(),
                    state.rewrittenQuery(), state.intent());
        } catch (Exception e) {
            log.warn("[memory] 长期记忆写入失败，已忽略: {}", e.getMessage());
        }
        Map<String, Object> u = new HashMap<>();
        u.put("steps", trace(state, "memory: 长期记忆已更新"));
        return u;
    }

    private Map<String, Object> clarify(TicketState state) {
        Map<String, Object> u = new HashMap<>();
        u.put("status", "WAITING_FOR_USER");
        u.put("steps", trace(state, "clarify: 挂起等待用户补充 " + state.missingSlots()));
        return u;
    }

    // ============ 辅助 ============

    private String fallbackResponse(TicketState state) {
        if (state.rejected()) {
            return "好的，已为您取消该操作，未做任何更改。如需其他帮助请随时告诉我。";
        }
        boolean anySuccess = false;
        for (ToolResult tr : state.toolResults()) {
            if (tr.isSuccess()) {
                anySuccess = true;
                break;
            }
        }
        if (!state.verified() && !anySuccess && !state.toolResults().isEmpty()) {
            return "抱歉，该操作执行未成功：" + state.verificationFeedback() + "。已为您转人工跟进。";
        }
        return "已收到您的请求，但暂时无法生成详细回复，请稍后再试或转人工处理。";
    }

    private ProcessContext ctx(TicketState state) {
        return new ProcessContext(state.conversationId(), state.customerId());
    }

    private List<String> toolNames(List<ToolCall> calls) {
        List<String> names = new ArrayList<>();
        for (ToolCall c : calls) {
            names.add(c.toolName());
        }
        return names;
    }

    private PlanStep firstGated(List<PlanStep> plan) {
        for (PlanStep s : plan) {
            if (s.isTool() && actionExecutor.requiresConfirmation(s.toolName())) {
                return s;
            }
        }
        return null;
    }

    private TicketIntent parseIntent(String s) {
        try {
            return TicketIntent.valueOf(s);
        } catch (Exception e) {
            return TicketIntent.UNKNOWN;
        }
    }

    private String buildClarifyQuestion(List<String> slots) {
        if (slots == null || slots.isEmpty()) {
            return "请补充必要的信息，以便我为您处理。";
        }
        return "为帮您处理，请补充以下信息：" + String.join("、", slots) + "。";
    }

    private String buildConfirmPrompt(PlanStep step) {
        Object orderNo = step.arguments() != null ? step.arguments().get("orderNo") : null;
        String no = orderNo != null ? String.valueOf(orderNo) : "";
        return switch (step.toolName()) {
            case "cancel_order" -> String.format(
                    "您确认要取消订单 %s 吗？取消后不可恢复，款项将原路退回。请确认或取消该操作。", no);
            case "create_after_sale" -> String.format(
                    "您确认要为订单 %s 申请售后退款吗？确认后订单将进入退款中，款项原路退回。请确认或取消该操作。", no);
            case "pay_order" -> String.format(
                    "您确认要支付订单 %s 吗？支付成功后订单将进入待发货。请确认或取消该操作。", no);
            case "process_refund" -> String.format(
                    "您确认要为订单 %s 完成退款到账吗？确认后款项将原路退回。请确认或取消该操作。", no);
            default -> "您确认要执行该敏感操作吗？" + (no.isBlank() ? "" : "（订单：" + no + "）");
        };
    }

    private String describePlan(List<PlanStep> plan) {
        List<String> d = new ArrayList<>();
        for (PlanStep s : plan) {
            d.add(s.isRetrieve() ? "RETRIEVE" : ("TOOL:" + s.toolName()));
        }
        return "[" + String.join(" → ", d) + "]";
    }

    private List<String> trace(TicketState state, String entry) {
        List<String> s = new ArrayList<>(state.steps());
        s.add(entry);
        return s;
    }
}
