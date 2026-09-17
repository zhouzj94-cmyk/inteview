package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.gateway.AiIntentRecognizer;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.IntentRecognitionResult;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.ProcessContext;
import com.zzj.interview.domain.gateway.AiIntentRecognizer.ToolCall;
import com.zzj.interview.domain.memory.UserMemory;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.rag.KnowledgeBase;
import com.zzj.interview.domain.rag.KnowledgeChunk;
import com.zzj.interview.domain.tool.BusinessTool;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.tool.BusinessActionExecutor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan-and-Execute 图的路由/中断/重规划测试
 *
 * 用 mock 的 AI/知识库/记忆端口 + 桩工具驱动真实的 LangGraphTicketWorkflow，
 * 隔离验证图的受控边（不依赖 Spring/DB）：
 * - 工具路径 / 知识库路径的分流（"查知识库还是调工具"）
 * - 敏感动作在 confirm 前中断（S1），批准后续跑执行、拒绝后跳过
 * - 澄清路径（AI 标记 或 选项C：动作缺必填参数）
 * - 验证失败触发一次重规划重试，仍失败则诚实反馈
 */
@DisplayName("Plan-and-Execute 图路由测试")
class LangGraphPlanExecuteTest {

    // ============ 桩工具 ============

    static class StubTool implements BusinessTool {
        final AtomicInteger calls = new AtomicInteger();
        private final String name;
        private final boolean gated;
        private final List<ToolParameter> params;
        private final Function<Map<String, Object>, ToolResult> fn;

        StubTool(String name, boolean gated, List<ToolParameter> params,
                 Function<Map<String, Object>, ToolResult> fn) {
            this.name = name;
            this.gated = gated;
            this.params = params;
            this.fn = fn;
        }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public boolean requiresConfirmation() { return gated; }
        @Override public List<ToolParameter> parameters() { return params; }
        @Override public ToolResult execute(Map<String, Object> a) {
            calls.incrementAndGet();
            return fn.apply(a);
        }
    }

    private static final List<BusinessTool.ToolParameter> ORDER_NO_REQUIRED =
            List.of(BusinessTool.ToolParameter.requiredString("orderNo", "订单号"));

    private StubTool queryTool(ToolResult fixed) {
        return new StubTool("query_order", false, ORDER_NO_REQUIRED, a -> fixed);
    }

    private StubTool cancelTool(ToolResult fixed) {
        return new StubTool("cancel_order", true, ORDER_NO_REQUIRED, a -> fixed);
    }

    // ============ mock 端口 ============

    private AiIntentRecognizer aiReturning(IntentRecognitionResult result) {
        AiIntentRecognizer ai = mock(AiIntentRecognizer.class);
        when(ai.rewriteQuery(anyString(), any(ProcessContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ai.recognize(anyString(), any(ProcessContext.class))).thenReturn(result);
        when(ai.generateResponse(anyString(), any(TicketIntent.class), anyList(), any(ProcessContext.class)))
                .thenAnswer(inv -> "回复[" + inv.getArgument(1) + "]");
        return ai;
    }

    private KnowledgeBase emptyKb() {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        when(kb.retrieve(anyString(), anyInt())).thenReturn(List.of());
        return kb;
    }

    private KnowledgeBase kbWith(String... contents) {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (String c : contents) {
            chunks.add(new KnowledgeChunk("id", c, "cat", List.of()));
        }
        when(kb.retrieve(anyString(), anyInt())).thenReturn(chunks);
        return kb;
    }

    private LangGraphTicketWorkflow workflow(AiIntentRecognizer ai, KnowledgeBase kb, BusinessTool... tools) {
        BusinessActionExecutor executor = new BusinessActionExecutor(List.of(tools));
        return new LangGraphTicketWorkflow(ai, kb, executor, new ActionIntentResolver(), mock(UserMemory.class));
    }

    private static IntentRecognitionResult resultOf(TicketIntent intent, ToolCall... calls) {
        return new IntentRecognitionResult(intent, 0.9, "推理", List.of(calls));
    }

    private Map<String, Object> inputs() {
        Map<String, Object> in = new HashMap<>();
        in.put("ticketId", "T1");
        in.put("conversationId", "CV1");
        in.put("customerId", "C1");
        in.put("content", "原始诉求");
        return in;
    }

    // ============ 驱动辅助 ============

    private List<NodeOutput<TicketState>> run(CompiledGraph<TicketState> g, String thread) {
        RunnableConfig cfg = RunnableConfig.builder().threadId(thread).build();
        List<NodeOutput<TicketState>> outs = new ArrayList<>();
        for (NodeOutput<TicketState> n : g.stream(inputs(), cfg)) outs.add(n);
        return outs;
    }

    private List<NodeOutput<TicketState>> resume(CompiledGraph<TicketState> g, String thread, boolean approved)
            throws Exception {
        RunnableConfig cfg = RunnableConfig.builder().threadId(thread).build();
        RunnableConfig rc = g.updateState(cfg, Map.of("approved", approved));
        List<NodeOutput<TicketState>> outs = new ArrayList<>();
        for (NodeOutput<TicketState> n : g.stream((Map<String, Object>) null, rc)) outs.add(n);
        return outs;
    }

    private String next(CompiledGraph<TicketState> g, String thread) {
        return g.getState(RunnableConfig.builder().threadId(thread).build()).next();
    }

    private TicketState finalState(List<NodeOutput<TicketState>> outs) {
        return outs.get(outs.size() - 1).state();
    }

    private List<String> names(List<NodeOutput<TicketState>> outs) {
        List<String> n = new ArrayList<>();
        for (NodeOutput<TicketState> o : outs) n.add(o.node());
        return n;
    }

    // ============ 测试 ============

    @Test
    @DisplayName("工具路径：query_order → execute_tool → verify → respond → memory，不检索不中断")
    void toolPath_executesAndResponds() {
        StubTool q = queryTool(ToolResult.success("query_order", "{\"status\":\"已发货\"}"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.ORDER_QUERY, new ToolCall("query_order", Map.of("orderNo", "10086")))),
                emptyKb(), q);

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-tool");
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("execute_tool"), "应执行工具，实际=" + nodes);
        assertTrue(nodes.contains("verify") && nodes.contains("respond") && nodes.contains("memory"),
                "应经过验证/回复/记忆，实际=" + nodes);
        assertFalse(nodes.contains("retrieve"), "有工具时不应检索知识库，实际=" + nodes);
        assertFalse(nodes.contains("confirm"), "只读工具不应中断确认，实际=" + nodes);
        assertEquals(1, q.calls.get());

        TicketState fin = finalState(outs);
        assertEquals("HANDLED", fin.status());
        assertTrue(fin.verified());
        assertEquals(TicketIntent.ORDER_QUERY.name(), fin.intent());
        assertFalse(fin.response().isBlank());
    }

    @Test
    @DisplayName("知识库路径：无工具 → retrieve 查知识库作答")
    void knowledgePath_retrievesWhenNoTool() {
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.AFTER_SALE)),   // 无 toolCalls
                kbWith("退货政策：7天无理由", "退款3-5工作日到账"));

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-kb");
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("retrieve"), "无工具应走知识库检索，实际=" + nodes);
        assertFalse(nodes.contains("execute_tool"), "无工具不应执行工具，实际=" + nodes);
        TicketState fin = finalState(outs);
        assertEquals("HANDLED", fin.status());
        assertEquals(2, fin.retrievedContext().size());
    }

    @Test
    @DisplayName("敏感动作：cancel_order 在 confirm 前中断（S1），next=confirm 且未执行")
    void sensitiveAction_interruptsBeforeConfirm() {
        StubTool c = cancelTool(ToolResult.success("cancel_order", "已取消"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.CANCEL_ORDER, new ToolCall("cancel_order", Map.of("orderNo", "10086")))),
                emptyKb(), c);

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-confirm");
        List<String> nodes = names(outs);

        assertEquals("confirm", next(wf.getGraph(), "t-confirm"), "应停在 confirm 断点");
        assertFalse(nodes.contains("confirm"), "中断在 confirm 之前，不应执行 confirm 体，实际=" + nodes);
        assertFalse(nodes.contains("execute_tool"), "中断时不应执行敏感工具，实际=" + nodes);
        assertEquals(0, c.calls.get(), "取消工具此刻绝不能被调用");

        TicketState st = finalState(outs);
        assertTrue(st.needsConfirm());
        assertEquals("cancel_order", st.pendingTool());
        assertEquals("10086", st.pendingArgs().get("orderNo"));
        assertFalse(st.confirmPrompt().isBlank());
    }

    @Test
    @DisplayName("确认续跑（批准）：confirm→execute_tool→verify→respond，取消工具被执行一次")
    void resumeApproved_executesSensitiveTool() throws Exception {
        StubTool c = cancelTool(ToolResult.success("cancel_order", "{\"status\":\"已取消\",\"verified\":true}"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.CANCEL_ORDER, new ToolCall("cancel_order", Map.of("orderNo", "10086")))),
                emptyKb(), c);

        run(wf.getGraph(), "t-approve");                       // 停在 confirm
        List<NodeOutput<TicketState>> outs = resume(wf.getGraph(), "t-approve", true);
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("confirm") && nodes.contains("execute_tool"),
                "批准后应执行 confirm→execute_tool，实际=" + nodes);
        assertTrue(nodes.contains("respond") && nodes.contains("memory"), "应走到回复/记忆，实际=" + nodes);
        assertEquals(1, c.calls.get(), "取消工具应被真正执行一次");

        TicketState fin = finalState(outs);
        assertEquals("HANDLED", fin.status());
        assertTrue(fin.verified());
        assertTrue(fin.toolResults().get(0).isSuccess());
    }

    @Test
    @DisplayName("确认续跑（拒绝）：跳过执行，取消工具零调用，诚实生成已取消回复")
    void resumeRejected_skipsExecution() throws Exception {
        StubTool c = cancelTool(ToolResult.success("cancel_order", "已取消"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.CANCEL_ORDER, new ToolCall("cancel_order", Map.of("orderNo", "10086")))),
                emptyKb(), c);

        run(wf.getGraph(), "t-reject");
        List<NodeOutput<TicketState>> outs = resume(wf.getGraph(), "t-reject", false);
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("confirm"), "拒绝也应执行 confirm 体，实际=" + nodes);
        assertFalse(nodes.contains("execute_tool"), "拒绝后绝不能执行敏感工具，实际=" + nodes);
        assertEquals(0, c.calls.get(), "取消工具零调用");

        TicketState fin = finalState(outs);
        assertEquals("HANDLED", fin.status());
        assertTrue(fin.rejected());
        assertTrue(fin.verified(), "用户拒绝视为无需校验，验证通过");
    }

    @Test
    @DisplayName("澄清路径（AI标记）：plan→clarify→END，状态 WAITING_FOR_USER")
    void clarifyPath_whenAiFlags() {
        IntentRecognitionResult r = new IntentRecognitionResult(
                TicketIntent.CANCEL_ORDER, 0.4, "缺订单号", List.of(),
                true, "请提供要取消的订单号", List.of("orderNo"));
        LangGraphTicketWorkflow wf = workflow(aiReturning(r), emptyKb(),
                cancelTool(ToolResult.success("cancel_order", "x")));

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-clarify");
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("clarify"), "应走澄清节点，实际=" + nodes);
        assertFalse(nodes.contains("execute_tool"), "澄清时不执行工具，实际=" + nodes);
        TicketState fin = finalState(outs);
        assertEquals("WAITING_FOR_USER", fin.status());
        assertTrue(fin.needsClarification());
        assertEquals("请提供要取消的订单号", fin.clarificationQuestion());
    }

    @Test
    @DisplayName("澄清路径（选项C）：AI未标记澄清但动作缺必填 orderNo → 由动作参数驱动澄清")
    void clarifyPath_whenActionMissingRequiredParam() {
        // needsClarification=false，但 cancel_order 的 args 缺 orderNo
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.CANCEL_ORDER, new ToolCall("cancel_order", Map.of()))),
                emptyKb(), cancelTool(ToolResult.success("cancel_order", "x")));

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-clarify-c");
        List<String> nodes = names(outs);

        assertTrue(nodes.contains("clarify"), "缺必填参数应触发澄清，实际=" + nodes);
        TicketState fin = finalState(outs);
        assertEquals("WAITING_FOR_USER", fin.status());
        assertTrue(fin.missingSlots().contains("orderNo"), "缺失槽位应含 orderNo，实际=" + fin.missingSlots());
    }

    @Test
    @DisplayName("验证失败→重规划重试一次→成功：execute_tool 跑两次，replanCount=1，终态通过")
    void verifyFailure_replansOnceThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        StubTool flaky = new StubTool("query_order", false, ORDER_NO_REQUIRED, a ->
                n.getAndIncrement() == 0
                        ? ToolResult.failure("query_order", "DB超时")
                        : ToolResult.success("query_order", "{\"status\":\"已发货\"}"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.ORDER_QUERY, new ToolCall("query_order", Map.of("orderNo", "10086")))),
                emptyKb(), flaky);

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-replan");
        List<String> nodes = names(outs);

        assertEquals(2, flaky.calls.get(), "失败后应重试一次，共执行两次");
        long verifyCount = nodes.stream().filter("verify"::equals).count();
        assertTrue(verifyCount >= 2, "应至少验证两次（失败+重试后），实际=" + nodes);
        long planCount = nodes.stream().filter(x -> x.equals("plan")).count();
        assertTrue(planCount >= 2, "应发生重规划（plan 再次执行），实际=" + nodes);

        TicketState fin = finalState(outs);
        assertEquals(1, fin.replanCount());
        assertTrue(fin.verified());
        assertEquals("HANDLED", fin.status());
    }

    @Test
    @DisplayName("验证失败且重规划耗尽：诚实反馈失败，不谎报成功")
    void verifyFailure_exhaustsReplan_honestFailure() {
        StubTool alwaysFail = new StubTool("query_order", false, ORDER_NO_REQUIRED,
                a -> ToolResult.failure("query_order", "订单不存在"));
        LangGraphTicketWorkflow wf = workflow(
                aiReturning(resultOf(TicketIntent.ORDER_QUERY, new ToolCall("query_order", Map.of("orderNo", "99999")))),
                emptyKb(), alwaysFail);

        List<NodeOutput<TicketState>> outs = run(wf.getGraph(), "t-fail");
        TicketState fin = finalState(outs);

        assertEquals(1, fin.replanCount(), "最多重规划一次");
        assertEquals(MAX_REPLAN_ATTEMPTS, alwaysFail.calls.get(), "首次+重试各一次");
        assertFalse(fin.verified(), "始终失败则验证不通过");
        assertEquals("HANDLED", fin.status(), "仍应生成回复（诚实失败），而非卡死");
        assertFalse(fin.response().isBlank());
    }

    /** 与图内 MAX_REPLAN=1 对应：首次执行 + 1 次重试 = 2 */
    private static final int MAX_REPLAN_ATTEMPTS = 2;
}
