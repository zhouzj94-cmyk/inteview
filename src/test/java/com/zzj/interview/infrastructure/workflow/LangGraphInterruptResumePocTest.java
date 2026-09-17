package com.zzj.interview.infrastructure.workflow;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 人机确认（HITL）机制隔离验证 PoC
 *
 * 目的：在改动真实编排前，用一张最小图跑通 LangGraph4j 1.8.19 的
 * "中断 → 检查点 → 注入人工输入 → 从断点续跑" 全链路，确认 API 行为无误。
 *
 * 图结构：
 *   START → plan ──route──┬─ 需确认 → confirm(中断点) → execute → END
 *                         └─ 不需确认 → execute → END
 *
 * 验证点：
 *   1. 首次 stream 在 confirm 之前停住（不执行 confirm/execute）
 *   2. getState().next() == "confirm"（断点位置正确，且状态已从检查点恢复）
 *   3. updateState 注入 approved=true，返回续跑用的 RunnableConfig
 *   4. stream(null, 续跑config) 从断点继续，执行 confirm → execute → END
 *   5. 终态 executed=true 且 approved=true（人工输入确实被节点读到）
 */
class LangGraphInterruptResumePocTest {

    /** PoC 专用最小状态：仅承载标记位，避免引入 Channel/Reducer 复杂度 */
    static class PocState extends AgentState {
        PocState(Map<String, Object> data) {
            super(data);
        }
        boolean needsConfirm() { return this.<Boolean>value("needsConfirm").orElse(false); }
        boolean approved()     { return this.<Boolean>value("approved").orElse(false); }
        boolean executed()     { return this.<Boolean>value("executed").orElse(false); }
        boolean confirmed()    { return this.<Boolean>value("confirmed").orElse(false); }
    }

    private CompiledGraph<PocState> buildGraph() throws Exception {
        StateGraph<PocState> sg = new StateGraph<>(PocState::new)
                .addNode("plan", AsyncNodeAction.node_async(state -> {
                    Map<String, Object> u = new HashMap<>();
                    u.put("planned", true);
                    return u;
                }))
                .addNode("confirm", AsyncNodeAction.node_async(state -> {
                    Map<String, Object> u = new HashMap<>();
                    u.put("confirmed", true);
                    return u;
                }))
                .addNode("execute", AsyncNodeAction.node_async(state -> {
                    Map<String, Object> u = new HashMap<>();
                    u.put("executed", true);
                    return u;
                }));

        sg.addEdge(StateGraph.START, "plan");
        sg.addConditionalEdges("plan",
                AsyncEdgeAction.edge_async(state -> state.needsConfirm() ? "to_confirm" : "to_execute"),
                Map.of("to_confirm", "confirm", "to_execute", "execute"));
        sg.addEdge("confirm", "execute");
        sg.addEdge("execute", StateGraph.END);

        // 关键：检查点 + 在 confirm 节点前中断
        return sg.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore("confirm")
                .build());
    }

    /** 收集一次 stream 的全部节点输出（NodeOutput 自带运行到该节点后的 state） */
    private static List<NodeOutput<PocState>> drain(org.bsc.async.AsyncGenerator<NodeOutput<PocState>> gen) {
        List<NodeOutput<PocState>> outs = new ArrayList<>();
        for (NodeOutput<PocState> n : gen) {
            outs.add(n);
        }
        return outs;
    }

    private static List<String> nodeNames(List<NodeOutput<PocState>> outs) {
        List<String> names = new ArrayList<>();
        for (NodeOutput<PocState> n : outs) names.add(n.node());
        return names;
    }

    /** 取流中最后一个携带状态的输出（END 节点也会带最终 state） */
    private static PocState lastState(List<NodeOutput<PocState>> outs) {
        return outs.get(outs.size() - 1).state();
    }

    @Test
    @DisplayName("S1：需确认时图在 confirm 前中断，注入 approved 后从断点续跑到 END")
    void interruptThenResumeWhenConfirmationNeeded() throws Exception {
        CompiledGraph<PocState> graph = buildGraph();
        RunnableConfig config = RunnableConfig.builder().threadId("cid-1").build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("needsConfirm", true);

        // 1) 首次运行：应在 confirm 前停住
        List<NodeOutput<PocState>> firstOuts = drain(graph.stream(inputs, config));
        List<String> firstRun = nodeNames(firstOuts);
        assertTrue(firstRun.contains("plan"), "首次运行应执行 plan，实际=" + firstRun);
        assertFalse(firstRun.contains("confirm"), "中断生效时不应执行 confirm，实际=" + firstRun);
        assertFalse(firstRun.contains("execute"), "中断生效时不应执行 execute，实际=" + firstRun);

        // 2) 断点位置正确
        StateSnapshot<PocState> snapshot = graph.getState(config);
        assertEquals("confirm", snapshot.next(), "getState().next() 应指向中断节点 confirm");
        assertFalse(snapshot.state().executed(), "中断时 execute 尚未运行");

        // 3) 注入人工确认，拿到续跑 config
        RunnableConfig resumeConfig = graph.updateState(config, Map.of("approved", true));

        // 4) 从断点续跑：传 null 输入表示"不重置，从检查点继续"
        List<NodeOutput<PocState>> resumedOuts = drain(graph.stream((Map<String, Object>) null, resumeConfig));
        List<String> resumed = nodeNames(resumedOuts);
        assertTrue(resumed.contains("confirm"), "续跑应执行 confirm，实际=" + resumed);
        assertTrue(resumed.contains("execute"), "续跑应执行 execute，实际=" + resumed);

        // 5) 终态校验：以"流最后一个 NodeOutput 的 state"为准
        //    注意：getState(resumeConfig) 会返回 updateState 时被钉住的断点检查点，
        //    而非续跑后的最新状态——这是必须避开的坑。要拿最新态，用 threadId-only config。
        PocState fin = lastState(resumedOuts);
        assertTrue(fin.executed(), "终态 executed 应为 true");
        assertTrue(fin.confirmed(), "终态 confirmed 应为 true");
        assertTrue(fin.approved(), "updateState 注入的 approved 应被保留");

        // 交叉验证：用仅含 threadId 的 config 查最新检查点，同样应看到 executed=true
        RunnableConfig threadOnly = RunnableConfig.builder().threadId("cid-1").build();
        assertTrue(graph.getState(threadOnly).state().executed(),
                "按 threadId 查最新检查点，executed 应为 true");
    }

    @Test
    @DisplayName("S1 边界：停在 confirm 断点后，用新 inputs 在同一 threadId 上 start() 应重新从 START 跑")
    void abandonInterruptThenStartFreshOnSameThread() throws Exception {
        CompiledGraph<PocState> graph = buildGraph();
        RunnableConfig config = RunnableConfig.builder().threadId("cid-3").build();

        // 1) 首轮：需确认，停在 confirm 断点
        Map<String, Object> first = new HashMap<>();
        first.put("needsConfirm", true);
        List<NodeOutput<PocState>> firstOuts = drain(graph.stream(first, config));
        assertEquals("confirm", graph.getState(config).next(), "首轮应停在 confirm 断点");
        assertFalse(nodeNames(firstOuts).contains("execute"), "首轮不应执行 execute");

        // 2) 用户放弃确认、直接发新消息：不再需要确认。
        //    用"新 inputs + 同一 threadId"再次 start()，验证是否从 START 重跑（而非续跑到 confirm）。
        Map<String, Object> second = new HashMap<>();
        second.put("needsConfirm", false);
        List<NodeOutput<PocState>> secondOuts = drain(graph.stream(second, config));
        List<String> run2 = nodeNames(secondOuts);

        // 关键断言：新一轮应执行 plan 且直达 execute，绝不应停在/执行 confirm 断点
        assertTrue(run2.contains("plan"), "新 inputs 应从 START 重跑 plan，实际=" + run2);
        assertTrue(run2.contains("execute"), "新 inputs 不需确认应直达 execute，实际=" + run2);
        assertFalse(run2.contains("confirm"), "新一轮不应经过 confirm，实际=" + run2);
        assertTrue(lastState(secondOuts).executed(), "新一轮终态 executed 应为 true");
    }

    @Test
    @DisplayName("S1 对照组：不需确认时图一次性跑完，不触发中断")
    void noInterruptWhenConfirmationNotNeeded() throws Exception {
        CompiledGraph<PocState> graph = buildGraph();
        RunnableConfig config = RunnableConfig.builder().threadId("cid-2").build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("needsConfirm", false);

        List<NodeOutput<PocState>> outs = drain(graph.stream(inputs, config));
        List<String> run = nodeNames(outs);
        assertTrue(run.contains("plan"), "应执行 plan，实际=" + run);
        assertTrue(run.contains("execute"), "应直达 execute，实际=" + run);
        assertFalse(run.contains("confirm"), "不需确认时不应经过 confirm，实际=" + run);

        PocState fin = lastState(outs);
        assertTrue(fin.executed(), "终态 executed 应为 true");
        assertFalse(fin.approved(), "无需确认路径不应有 approved");
    }
}
