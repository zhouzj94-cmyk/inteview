package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.sse.SseEvents;
import com.zzj.interview.infrastructure.tool.BusinessActionExecutor;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单图驱动器（Plan-and-Execute 图的统一入口）
 *
 * 职责：驱动 {@link LangGraphTicketWorkflow} 的编译图，把每个 NodeOutput 映射为前端约定的
 * SSE 进度事件，并在流结束后判定本轮的终止原因（完成/需确认/需澄清/失败），
 * 返回 {@link RunOutcome} 供上层（processAsync、confirmAction）做领域落库与终态推送。
 *
 * 两类入口：
 * - {@link #start} 首次运行：stream(inputs, threadId=conversationId)
 * - {@link #resume} 确认续跑（S1）：updateState 注入 approved 后 stream(null, cfg) 恢复同一张图
 *
 * 关键：SseEmitter 不进入图状态；threadId=conversationId 保证多轮/确认命中同一检查点线程。
 */
@Component
public class TicketGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(TicketGraphRunner.class);

    private final LangGraphTicketWorkflow workflow;
    private final BusinessActionExecutor actionExecutor;
    private final ObjectMapper objectMapper;

    public TicketGraphRunner(LangGraphTicketWorkflow workflow,
                             BusinessActionExecutor actionExecutor,
                             ObjectMapper objectMapper) {
        this.workflow = workflow;
        this.actionExecutor = actionExecutor;
        this.objectMapper = objectMapper;
    }

    /** 本轮运行的终止原因 */
    public enum Reason {
        /** 正常完成，已生成回复 */
        COMPLETED,
        /** 命中敏感动作，图停在 confirm 断点，等待人工确认 */
        NEEDS_CONFIRM,
        /** 信息不足，图走 clarify 挂起，等待用户补充 */
        NEEDS_CLARIFICATION,
        /** 运行异常（如 AI 调用失败），需上层降级人工 */
        FAILED
    }

    /** 运行结果：终止原因 + 最终图状态（失败时可能为 null）+ 异常 */
    public record RunOutcome(Reason reason, TicketState state, Throwable error) {
        public boolean is(Reason r) {
            return reason == r;
        }
    }

    /**
     * 首次运行图。
     *
     * @param inputs         图输入（ticketId/conversationId/customerId/content）
     * @param emitter        SSE 发射器（用于推送进度事件）
     */
    public RunOutcome start(Map<String, Object> inputs, String ticketId,
                            String conversationId, SseEmitter emitter) {
        CompiledGraph<TicketState> graph = workflow.getGraph();
        RunnableConfig cfg = RunnableConfig.builder().threadId(conversationId).build();
        return drive(graph, cfg, graph.stream(inputs, cfg), ticketId, conversationId, emitter);
    }

    /**
     * 人工确认后恢复同一张图（S1）。
     *
     * @param approved 用户是否批准挂起的敏感操作
     * @param emitter  可为 null（确认接口是同步 HTTP，不需要进度推送）
     */
    public RunOutcome resume(String conversationId, boolean approved,
                             String ticketId, SseEmitter emitter) throws Exception {
        CompiledGraph<TicketState> graph = workflow.getGraph();
        RunnableConfig cfg = RunnableConfig.builder().threadId(conversationId).build();
        RunnableConfig resumeCfg = graph.updateState(cfg, Map.of("approved", approved));
        return drive(graph, cfg, graph.stream((Map<String, Object>) null, resumeCfg),
                ticketId, conversationId, emitter);
    }

    private RunOutcome drive(CompiledGraph<TicketState> graph, RunnableConfig threadCfg,
                             AsyncGenerator<NodeOutput<TicketState>> generator,
                             String ticketId, String conversationId, SseEmitter emitter) {
        TicketState last = null;
        try {
            for (NodeOutput<TicketState> out : generator) {
                if (out.state() != null) {
                    last = out.state();
                }
                emitProgress(out, ticketId, conversationId, emitter);
            }
        } catch (Exception e) {
            log.error("[graph] 运行异常，交上层降级: conversationId={}", conversationId, e);
            return new RunOutcome(Reason.FAILED, last, e);
        }

        // 判定终止原因：优先看是否停在 confirm 断点
        String nextNode = null;
        try {
            nextNode = graph.getState(threadCfg).next();
        } catch (Exception e) {
            log.warn("[graph] 读取断点状态失败: conversationId={}, err={}", conversationId, e.getMessage());
        }
        if (workflow.confirmNodeName().equals(nextNode)) {
            return new RunOutcome(Reason.NEEDS_CONFIRM, last, null);
        }
        if (last == null) {
            return new RunOutcome(Reason.FAILED, null, new IllegalStateException("图未产生任何输出"));
        }
        if (last.needsClarification() && "WAITING_FOR_USER".equals(last.status())) {
            return new RunOutcome(Reason.NEEDS_CLARIFICATION, last, null);
        }
        return new RunOutcome(Reason.COMPLETED, last, null);
    }

    /**
     * 把节点输出映射为前端约定的 SSE 进度事件。
     * 只发前端 App.vue 已处理的事件名：query_rewritten / ai_analyzed / tool_dispatching / tool_result。
     */
    private void emitProgress(NodeOutput<TicketState> out, String ticketId,
                              String conversationId, SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        TicketState s = out.state();
        if (s == null) {
            return;
        }
        switch (out.node()) {
            case "rewrite" -> {
                if (!s.rewrittenQuery().equals(s.content())) {
                    SseEvents.send(emitter, objectMapper, "query_rewritten", Map.of(
                            "ticketId", ticketId,
                            "conversationId", conversationId,
                            "original", s.content(),
                            "rewritten", s.rewrittenQuery()));
                }
            }
            case "plan" -> {
                if (s.needsClarification()) {
                    return; // 澄清由上层发 clarification 终态事件
                }
                TicketIntent intent = parseIntent(s.intent());
                SseEvents.send(emitter, objectMapper, "ai_analyzed", Map.of(
                        "ticketId", ticketId,
                        "conversationId", conversationId,
                        "intent", intent.name(),
                        "intentDescription", intent.getDescription(),
                        "confidence", s.confidence(),
                        "reasoning", s.reasoning() != null ? s.reasoning() : ""));
                // 只对"可自动执行"（非敏感）的工具预告 tool_dispatching，敏感动作走确认流程
                List<String> autoTools = new ArrayList<>();
                for (PlanStep step : s.plan()) {
                    if (step.isTool() && !actionExecutor.requiresConfirmation(step.toolName())) {
                        autoTools.add(step.toolName());
                    }
                }
                if (!autoTools.isEmpty()) {
                    SseEvents.send(emitter, objectMapper, "tool_dispatching", Map.of(
                            "conversationId", conversationId,
                            "tools", autoTools));
                }
            }
            case "execute_tool" -> {
                List<ToolResult> results = s.toolResults();
                if (!results.isEmpty()) {
                    ToolResult tr = results.get(results.size() - 1);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("conversationId", conversationId);
                    payload.put("toolName", tr.getToolName());
                    payload.put("success", tr.isSuccess());
                    payload.put("data", tr.isSuccess() ? tr.getData() : tr.getErrorMessage());
                    SseEvents.send(emitter, objectMapper, "tool_result", payload);
                }
            }
            default -> {
                // retrieve/verify/respond/memory/confirm/clarify 不发进度事件（前端不消费或属终态）
            }
        }
    }

    private TicketIntent parseIntent(String s) {
        try {
            return TicketIntent.valueOf(s);
        } catch (Exception e) {
            return TicketIntent.UNKNOWN;
        }
    }
}
