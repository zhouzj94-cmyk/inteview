package com.zzj.interview.infrastructure.sse;

import tools.jackson.databind.ObjectMapper;
import com.zzj.interview.domain.memory.ConversationMemory;
import com.zzj.interview.domain.model.conversation.Conversation;
import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketAiRecord;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.domain.repository.ConversationRepository;
import com.zzj.interview.domain.repository.TicketRepository;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner.RunOutcome;
import com.zzj.interview.infrastructure.workflow.TicketState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 工单异步处理器（多轮对话编排入口）
 *
 * 职责已从"命令式编排"收敛为"驱动 Plan-and-Execute 图 + 领域落库 + 终态SSE"：
 * 真正的规划（查知识库 or 调工具）、执行、结果验证、回复生成都发生在
 * {@link com.zzj.interview.infrastructure.workflow.LangGraphTicketWorkflow} 图内，
 * 由 {@link TicketGraphRunner} 驱动并把节点进度映射为SSE事件。
 *
 * 本处理器只负责图之外的领域协作：
 * 1. 会话状态衔接（beginTurn / 澄清补充合并 / 放弃过期确认）
 * 2. 依据图的终止原因（{@link RunOutcome}）更新工单&会话状态并持久化
 * 3. 推送终态SSE事件（response / clarification / confirmation_required / error / done）
 * 4. 澄清问题补写短期记忆（图内 respond 走 generateResponse 已自带助手轮写入）
 *
 * 关键设计：AI调用属外部IO，不放在数据库事务中；先跑完图拿到结果再开本地事务落库。
 */
@Component
public class TicketAsyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(TicketAsyncProcessor.class);

    private final TicketGraphRunner graphRunner;
    private final TicketRepository ticketRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemory conversationMemory;
    private final ObjectMapper objectMapper;

    public TicketAsyncProcessor(TicketGraphRunner graphRunner,
                                 TicketRepository ticketRepository,
                                 ConversationRepository conversationRepository,
                                 ConversationMemory conversationMemory,
                                 ObjectMapper objectMapper) {
        this.graphRunner = graphRunner;
        this.ticketRepository = ticketRepository;
        this.conversationRepository = conversationRepository;
        this.conversationMemory = conversationMemory;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步处理一轮对话：驱动 Plan-and-Execute 图，再依据终止原因落库与推送终态。
     */
    @Async("ticketExecutor")
    public void processAsync(Ticket ticket, Conversation conversation, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        String conversationId = conversation.getConversationId();
        try {
            // 步骤0：会话状态衔接（澄清补充 / 放弃过期确认）
            String content = ticket.getContent();
            conversation.beginTurn(ticket.getId());
            if (conversation.hasPendingClarification()) {
                Conversation.PendingClarification prior = conversation.consumeClarification();
                // 合并"上一轮原始诉求 + 本轮用户补充"，形成完整query交给图重新规划
                content = ((prior.originalContent() == null ? "" : prior.originalContent() + " ")
                        + ticket.getContent()).trim();
                log.info("[会话:{}] 合并澄清补充: {}", conversationId, content);
            } else if (conversation.hasPendingAction()) {
                // 用户未走确认接口而是直接发了新消息 → 放弃挂起的确认动作。
                // 图侧以新 inputs 在同一 threadId 上 start() 会从 START 重新规划，不会续跑到旧断点。
                conversation.consumePendingAction();
                log.info("[会话:{}] 用户发起新诉求，放弃挂起的待确认操作", conversationId);
            }

            SseEvents.send(emitter, objectMapper, "ai_analyzing", Map.of(
                    "ticketId", ticket.getId(),
                    "conversationId", conversationId,
                    "message", "AI正在分析您的工单..."
            ));

            // 步骤1：构造图输入并驱动图（threadId=conversationId，命中同一检查点线程）
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("ticketId", ticket.getId());
            inputs.put("conversationId", conversationId);
            inputs.put("customerId", ticket.getCustomerId());
            inputs.put("content", content);

            RunOutcome outcome = graphRunner.start(inputs, ticket.getId(), conversationId, emitter);

            // 步骤2：依据终止原因做领域落库 + 终态SSE
            switch (outcome.reason()) {
                case COMPLETED -> handleCompleted(ticket, conversation, emitter, outcome.state(), startTime);
                case NEEDS_CONFIRM -> handleConfirmRequired(ticket, conversation, emitter, outcome.state());
                case NEEDS_CLARIFICATION -> handleClarification(ticket, conversation, emitter, outcome.state());
                case FAILED -> {
                    Throwable err = outcome.error() != null
                            ? outcome.error()
                            : new IllegalStateException("图运行失败");
                    log.error("工单处理失败: ticketId={}", ticket.getId(), err);
                    handleProcessingFailure(ticket, emitter, err);
                }
            }
        } catch (Exception e) {
            log.error("工单处理失败: ticketId={}", ticket.getId(), e);
            handleProcessingFailure(ticket, emitter, e);
        }
    }

    /**
     * 终止原因=完成：绑定派生意图、落库回复、写AI调用日志，推送 response/done。
     * 注：长期记忆由图内 memory 节点写入；短期助手轮由 respond 节点的 generateResponse 写入。
     */
    private void handleCompleted(Ticket ticket, Conversation conversation, SseEmitter emitter,
                                 TicketState state, long startTime) {
        String conversationId = conversation.getConversationId();
        TicketIntent intent = parseIntent(state.intent());
        String response = state.response();

        ticket.bindIntent(intent);
        ticket.markHandled(response);

        long latency = System.currentTimeMillis() - startTime;
        TicketAiRecord aiRecord = TicketAiRecord.create(
                "AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                ticket.getId(), "qwen-plus",
                ticket.getContent(), response,
                intent.name(), latency, true
        );

        ticketRepository.save(ticket);
        ticketRepository.saveAiRecord(aiRecord);
        conversationRepository.save(conversation);

        SseEvents.send(emitter, objectMapper, "response", Map.of(
                "ticketId", ticket.getId(),
                "conversationId", conversationId,
                "message", response
        ));
        SseEvents.send(emitter, objectMapper, "done", Map.of(
                "ticketId", ticket.getId(),
                "conversationId", conversationId,
                "status", ticket.getStatus().name(),
                "intent", intent.name()
        ));
        emitter.complete();
    }

    /**
     * 终止原因=需确认：图停在 confirm 断点。挂起敏感动作到会话，推送 confirmation_required/done。
     * 真正执行延迟到确认接口调用 graphRunner.resume() 从断点续跑。
     */
    private void handleConfirmRequired(Ticket ticket, Conversation conversation,
                                       SseEmitter emitter, TicketState state) {
        String conversationId = conversation.getConversationId();
        String actionId = "ACT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String toolName = state.pendingTool();
        Map<String, Object> args = state.pendingArgs();
        String prompt = state.confirmPrompt();
        TicketIntent intent = parseIntent(state.intent());

        conversation.requestConfirmation(actionId, toolName, args, prompt);
        conversationRepository.save(conversation);

        // 工单仍是 CREATED：先绑定派生意图（CREATED→ANALYZING），再挂起等待确认（→WAITING_FOR_USER）
        ticket.bindIntent(intent);
        ticket.markWaitingForUser(prompt);
        ticketRepository.save(ticket);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", ticket.getId());
        payload.put("conversationId", conversationId);
        payload.put("actionId", actionId);
        payload.put("toolName", toolName);
        payload.put("arguments", args != null ? args : Map.of());
        payload.put("prompt", prompt);
        SseEvents.send(emitter, objectMapper, "confirmation_required", payload);
        SseEvents.send(emitter, objectMapper, "done", Map.of(
                "ticketId", ticket.getId(),
                "conversationId", conversationId,
                "status", ticket.getStatus().name(),
                "intent", intent.name()
        ));
        emitter.complete();
    }

    /**
     * 终止原因=需澄清：图走 clarify 挂起。记录缺失槽位与澄清问题到会话，推送 clarification/done。
     * 澄清问题也是一次"助手发言"，补写短期记忆，保证下一轮补充时上下文连贯
     * （图内 clarify 节点不调用 generateResponse，故此处需显式补写）。
     */
    private void handleClarification(Ticket ticket, Conversation conversation,
                                     SseEmitter emitter, TicketState state) {
        String conversationId = conversation.getConversationId();
        TicketIntent intent = parseIntent(state.intent());
        String question = state.clarificationQuestion() != null && !state.clarificationQuestion().isBlank()
                ? state.clarificationQuestion()
                : "请补充必要的信息，以便我为您处理。";

        conversation.requestClarification(
                ticket.getContent(), intent.name(), state.missingSlots(), question);
        conversationRepository.save(conversation);

        // 工单仍是 CREATED：先绑定派生意图（CREATED→ANALYZING），再挂起等待补充（→WAITING_FOR_USER）
        ticket.bindIntent(intent);
        ticket.markWaitingForUser(question);
        ticketRepository.save(ticket);

        safeRecordAssistantTurn(conversationId, question);

        SseEvents.send(emitter, objectMapper, "clarification", Map.of(
                "ticketId", ticket.getId(),
                "conversationId", conversationId,
                "question", question,
                "missingSlots", state.missingSlots() != null ? state.missingSlots() : java.util.List.of()
        ));
        SseEvents.send(emitter, objectMapper, "done", Map.of(
                "ticketId", ticket.getId(),
                "conversationId", conversationId,
                "status", ticket.getStatus().name(),
                "intent", intent.name()
        ));
        emitter.complete();
    }

    /**
     * 写入一轮"助手发言"到短期记忆（失败不影响主流程）
     * sessionId = conversationId，与识别器共用同一份会话短期记忆
     */
    private void safeRecordAssistantTurn(String sessionId, String text) {
        try {
            conversationMemory.addMessage(sessionId, ConversationMemory.Message.assistant(text, null));
        } catch (Exception e) {
            log.warn("写入助手轮短期记忆失败，已忽略: sessionId={}, err={}", sessionId, e.getMessage());
        }
    }

    private TicketIntent parseIntent(String s) {
        try {
            return TicketIntent.valueOf(s);
        } catch (Exception e) {
            return TicketIntent.UNKNOWN;
        }
    }

    /**
     * AI处理失败时的降级策略
     * 关键原则：AI失败不能导致业务系统直接挂掉
     */
    private void handleProcessingFailure(Ticket ticket, SseEmitter emitter, Throwable e) {
        try {
            ticket.markManualReview();
            ticketRepository.save(ticket);

            SseEvents.send(emitter, objectMapper, "error", Map.of(
                    "ticketId", ticket.getId(),
                    "message", "AI处理失败，已转为人工处理",
                    "error", e.getMessage() != null ? e.getMessage() : "未知错误"
            ));
            SseEvents.send(emitter, objectMapper, "done", Map.of(
                    "ticketId", ticket.getId(),
                    "status", "MANUAL_REVIEW"
            ));
            emitter.complete();
        } catch (Exception ex) {
            log.error("降级处理也失败了: ticketId={}", ticket.getId(), ex);
            emitter.completeWithError(ex);
        }
    }
}
