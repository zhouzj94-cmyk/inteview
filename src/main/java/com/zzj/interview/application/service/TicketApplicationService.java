package com.zzj.interview.application.service;

import com.zzj.interview.application.command.CreateTicketCommand;
import com.zzj.interview.application.result.ActionConfirmResult;
import com.zzj.interview.domain.model.conversation.Conversation;
import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.repository.ConversationRepository;
import com.zzj.interview.domain.repository.TicketRepository;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.sse.TicketAsyncProcessor;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner.RunOutcome;
import com.zzj.interview.infrastructure.workflow.TicketState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * 工单应用服务
 *
 * 职责：编排业务流程，不承载核心业务规则（Application层是"总导演"）。
 *
 * 本服务承载两类编排：
 * 1. createAndStream —— 开启/续接一轮多轮对话，异步驱动AI处理并通过SSE推送
 * 2. confirmAction   —— 人机交互确认点：用户批准/拒绝挂起的敏感操作（如取消订单）
 *
 * 两者都委托给同一张 Plan-and-Execute 图（threadId=conversationId）：
 * createAndStream 经 {@link TicketAsyncProcessor} 首次运行图；confirmAction 经
 * {@link TicketGraphRunner#resume} 从 confirm 断点注入 approved 续跑同一张图（S1）。
 * 因此确认后的工具执行、结果验证、回复生成、长短期记忆写入全部在图内完成，
 * 应用服务只做领域落库（工单状态）与结果回传。
 *
 * 注意：AI调用在异步线程中执行，不占用数据库事务。
 */
@Service
public class TicketApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TicketApplicationService.class);

    private final TicketRepository ticketRepository;
    private final ConversationRepository conversationRepository;
    private final TicketAsyncProcessor ticketAsyncProcessor;
    private final TicketGraphRunner graphRunner;

    public TicketApplicationService(TicketRepository ticketRepository,
                                     ConversationRepository conversationRepository,
                                     TicketAsyncProcessor ticketAsyncProcessor,
                                     TicketGraphRunner graphRunner) {
        this.ticketRepository = ticketRepository;
        this.conversationRepository = conversationRepository;
        this.ticketAsyncProcessor = ticketAsyncProcessor;
        this.graphRunner = graphRunner;
    }

    /**
     * 创建工单并启动SSE处理流
     *
     * 流程：解析/创建会话 → 生成工单 → 持久化 → 异步触发AI处理
     *
     * @return 工单ID
     */
    public String createAndStream(CreateTicketCommand command, SseEmitter emitter) {
        String ticketId = "TK" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 会话解析：客户端传conversationId则续接，否则新建一个会话
        String conversationId = (command.conversationId() != null && !command.conversationId().isBlank())
                ? command.conversationId()
                : "CV" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Conversation conversation = conversationRepository.findById(conversationId);
        if (conversation == null) {
            conversation = Conversation.start(conversationId, command.customerId());
        }
        conversationRepository.save(conversation);

        // 通过聚合根工厂方法创建工单（业务规则在Domain内部校验）
        Ticket ticket = Ticket.create(ticketId, command.customerId(), command.content());
        ticketRepository.save(ticket);

        log.info("工单已创建: id={}, customerId={}, conversationId={}, turn={}",
                ticketId, command.customerId(), conversationId, conversation.getTurnCount() + 1);

        // 异步处理（独立线程，不阻塞HTTP、不占用事务）
        ticketAsyncProcessor.processAsync(ticket, conversation, emitter);

        return ticketId;
    }

    /**
     * 人机交互确认：批准或拒绝一个挂起的敏感操作。
     *
     * 从 confirm 断点恢复同一张图（S1）：注入 approved 后续跑，
     * 图内完成"执行敏感工具 → 结果验证 → 生成诚实回复 → 写长短期记忆"。
     * 应用服务只据图终态更新工单状态并回传结果。
     *
     * @param conversationId 会话ID
     * @param actionId       挂起动作ID（来自 confirmation_required 事件）
     * @param approved       用户是否批准
     */
    public ActionConfirmResult confirmAction(String conversationId, String actionId, boolean approved) {
        Conversation conversation = conversationRepository.findById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }
        Conversation.PendingAction pending = conversation.getPendingAction();
        if (pending == null) {
            throw new IllegalStateException("当前会话没有待确认的操作");
        }
        if (actionId != null && !actionId.isBlank() && !actionId.equals(pending.actionId())) {
            throw new IllegalArgumentException("待确认动作ID不匹配");
        }

        // 无论批准与否，都消费掉挂起动作（一次性），会话状态回到 ACTIVE
        conversation.consumePendingAction();

        String ticketId = conversation.getCurrentTicketId();
        Ticket ticket = ticketId != null ? ticketRepository.findById(ticketId) : null;
        String decision = approved ? "APPROVED" : "REJECTED";

        // 从 confirm 断点恢复同一张图（emitter=null：确认是同步HTTP，不需要进度推送）
        RunOutcome outcome;
        try {
            outcome = graphRunner.resume(conversationId, approved, ticketId, null);
        } catch (Exception e) {
            log.error("[会话:{}] 确认续跑图失败，降级人工: {}", conversationId, e.getMessage(), e);
            if (ticket != null) {
                ticket.markManualReview();
                ticketRepository.save(ticket);
            }
            conversationRepository.save(conversation);
            return new ActionConfirmResult(ticketId, conversationId, decision,
                    "确认操作处理失败，已转人工处理。", null);
        }

        TicketState state = outcome.state();
        String response = state != null ? state.response() : "";
        if (response == null || response.isBlank()) {
            response = approved
                    ? "已按您的确认完成操作。"
                    : "好的，已为您取消该操作，未对订单做任何更改。";
        }
        String toolData = extractLastToolData(state);

        if (ticket != null) {
            // 确认阶段工单已是 WAITING_FOR_USER，意图在挂起时已绑定；此处仅落最终回复
            ticket.markHandled(response);
            ticketRepository.save(ticket);
        }
        conversationRepository.save(conversation);

        log.info("[会话:{}] 用户{}操作 action={}, tool={}",
                conversationId, approved ? "批准并执行" : "拒绝", actionId, pending.toolName());
        return new ActionConfirmResult(ticketId, conversationId, decision, response, toolData);
    }

    /** 取图中最后一个工具结果的数据/错误信息，作为确认结果回传（无则 null） */
    private String extractLastToolData(TicketState state) {
        if (state == null) {
            return null;
        }
        List<ToolResult> results = state.toolResults();
        if (results == null || results.isEmpty()) {
            return null;
        }
        ToolResult tr = results.get(results.size() - 1);
        return tr.isSuccess() ? tr.getData() : tr.getErrorMessage();
    }
}
