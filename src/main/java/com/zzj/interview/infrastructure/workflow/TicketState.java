package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.tool.ToolResult;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单处理工作流状态（Plan-and-Execute）
 *
 * 继承 LangGraph4j 的 AgentState，承载工作流执行全过程的状态。
 * 所有值存放在 Map 中，检查点（MemorySaver）按引用持有，
 * 因此节点每步都应返回"新集合"，不要就地修改状态里的 List/Map。
 *
 * 状态分区：
 * - 输入：ticketId/conversationId/customerId/content
 * - 改写：rewrittenQuery
 * - 规划：plan(List<PlanStep>)/planCursor/replanCount/intent/confidence/reasoning
 * - 澄清：needsClarification/clarificationQuestion/missingSlots
 * - 确认：needsConfirm/pendingTool/pendingArgs/confirmPrompt/approved
 * - 检索：retrievedContext
 * - 执行：toolResults(List<ToolResult>)
 * - 验证：verified/verificationFeedback
 * - 输出：response/status
 * - 追踪：steps（每步推理记录，体现多步推理可追溯性）
 */
public class TicketState extends AgentState {

    public TicketState(Map<String, Object> data) {
        super(data);
    }

    // ============ 输入 ============

    public String ticketId() {
        return this.<String>value("ticketId").orElse("");
    }

    public String conversationId() {
        return this.<String>value("conversationId").orElse("");
    }

    public String customerId() {
        return this.<String>value("customerId").orElse("");
    }

    public String content() {
        return this.<String>value("content").orElse("");
    }

    // ============ 改写 ============

    public String rewrittenQuery() {
        return this.<String>value("rewrittenQuery").orElse(content());
    }

    // ============ 规划 ============

    @SuppressWarnings("unchecked")
    public List<PlanStep> plan() {
        return this.<List<PlanStep>>value("plan").orElse(List.of());
    }

    public int planCursor() {
        return this.<Integer>value("planCursor").orElse(0);
    }

    public int replanCount() {
        return this.<Integer>value("replanCount").orElse(0);
    }

    /** verify 失败后请求重规划的一次性标志，plan 节点消费后清除 */
    public boolean replanRequested() {
        return this.<Boolean>value("replanRequested").orElse(false);
    }

    public String intent() {
        return this.<String>value("intent").orElse("UNKNOWN");
    }

    public double confidence() {
        return this.<Double>value("confidence").orElse(0.0);
    }

    public String reasoning() {
        return this.<String>value("reasoning").orElse("");
    }

    /** 当前游标指向的步骤；越界返回 null */
    public PlanStep currentStep() {
        List<PlanStep> p = plan();
        int i = planCursor();
        return (i >= 0 && i < p.size()) ? p.get(i) : null;
    }

    // ============ 澄清 ============

    public boolean needsClarification() {
        return this.<Boolean>value("needsClarification").orElse(false);
    }

    public String clarificationQuestion() {
        return this.<String>value("clarificationQuestion").orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<String> missingSlots() {
        return this.<List<String>>value("missingSlots").orElse(List.of());
    }

    // ============ 确认（HITL） ============

    public boolean needsConfirm() {
        return this.<Boolean>value("needsConfirm").orElse(false);
    }

    public String pendingTool() {
        return this.<String>value("pendingTool").orElse("");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> pendingArgs() {
        return this.<Map<String, Object>>value("pendingArgs").orElse(Map.of());
    }

    public String confirmPrompt() {
        return this.<String>value("confirmPrompt").orElse("");
    }

    public boolean approved() {
        return this.<Boolean>value("approved").orElse(false);
    }

    /** 用户明确拒绝了挂起的敏感操作（跳过验证重试，直接生成"已取消"回复） */
    public boolean rejected() {
        return this.<Boolean>value("rejected").orElse(false);
    }

    // ============ 检索 ============

    @SuppressWarnings("unchecked")
    public List<String> retrievedContext() {
        return this.<List<String>>value("retrievedContext").orElse(List.of());
    }

    // ============ 执行 ============

    @SuppressWarnings("unchecked")
    public List<ToolResult> toolResults() {
        return this.<List<ToolResult>>value("toolResults").orElse(List.of());
    }

    // ============ 验证 ============

    public boolean verified() {
        return this.<Boolean>value("verified").orElse(false);
    }

    public String verificationFeedback() {
        return this.<String>value("verificationFeedback").orElse("");
    }

    // ============ 输出 ============

    public String response() {
        return this.<String>value("response").orElse("");
    }

    public String status() {
        return this.<String>value("status").orElse("CREATED");
    }

    // ============ 追踪 ============

    @SuppressWarnings("unchecked")
    public List<String> steps() {
        List<String> s = this.<List<String>>value("steps").orElse(new ArrayList<>());
        return s != null ? s : new ArrayList<>();
    }
}
