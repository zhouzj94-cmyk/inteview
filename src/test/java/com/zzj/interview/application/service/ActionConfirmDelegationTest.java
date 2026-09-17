package com.zzj.interview.application.service;

import com.zzj.interview.application.result.ActionConfirmResult;
import com.zzj.interview.domain.model.conversation.Conversation;
import com.zzj.interview.domain.model.ticket.Ticket;
import com.zzj.interview.domain.model.ticket.TicketStatus;
import com.zzj.interview.domain.repository.ConversationRepository;
import com.zzj.interview.domain.repository.TicketRepository;
import com.zzj.interview.domain.tool.ToolResult;
import com.zzj.interview.infrastructure.sse.TicketAsyncProcessor;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner.Reason;
import com.zzj.interview.infrastructure.workflow.TicketGraphRunner.RunOutcome;
import com.zzj.interview.infrastructure.workflow.TicketState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 人机确认「批准/拒绝」委托图续跑（S1）的应用服务测试
 *
 * 架构变更后，确认的执行/验证/回复生成/长短期记忆写入都发生在图内
 * （respond 节点的 generateResponse 写短期助手轮，memory 节点写长期记忆），
 * 应用服务只负责：校验挂起动作 → 消费挂起 → 从 confirm 断点续跑同一张图 →
 * 依据图终态落工单状态并回传 {@link ActionConfirmResult}。
 *
 * 本测试用打桩的 {@link TicketGraphRunner} 隔离图，聚焦上述委托与映射逻辑。
 */
@DisplayName("确认动作委托图续跑与结果映射测试")
class ActionConfirmDelegationTest {

    private static final String CONV_ID = "CV_confirm";
    private static final String ACTION_ID = "ACT_confirm1";
    private static final String TICKET_ID = "TK_confirm";

    /** 构造一个"挂起待确认动作 + 关联工单处于 WAITING_FOR_USER"的场景 */
    private TicketApplicationService buildService(TicketGraphRunner runner,
                                                  ConversationRepository convRepo,
                                                  TicketRepository ticketRepo) {
        return new TicketApplicationService(ticketRepo, convRepo, (TicketAsyncProcessor) null, runner);
    }

    private Conversation conversationWithPendingAction() {
        Conversation conversation = Conversation.start(CONV_ID, "C001");
        conversation.beginTurn(TICKET_ID);
        conversation.requestConfirmation(ACTION_ID, "cancel_order",
                Map.of("orderNo", "10086"), "您确认要取消订单 10086 吗？");
        return conversation;
    }

    private Ticket waitingTicket() {
        Ticket t = Ticket.create(TICKET_ID, "C001", "帮我取消订单10086");
        t.bindIntent(com.zzj.interview.domain.model.ticket.TicketIntent.CANCEL_ORDER);
        t.markWaitingForUser("您确认要取消订单 10086 吗？");
        return t;
    }

    private TicketState state(String response, String intent, List<ToolResult> results) {
        return new TicketState(Map.of(
                "response", response,
                "intent", intent,
                "status", "HANDLED",
                "toolResults", results));
    }

    @Test
    @DisplayName("批准 - 委托 resume(true) 续跑，映射图回复与工具数据，工单落 HANDLED")
    void approve_delegatesToResumeAndMapsOutcome() throws Exception {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        TicketGraphRunner runner = mock(TicketGraphRunner.class);

        Conversation conversation = conversationWithPendingAction();
        Ticket ticket = waitingTicket();
        when(convRepo.findById(CONV_ID)).thenReturn(conversation);
        when(ticketRepo.findById(TICKET_ID)).thenReturn(ticket);

        String graphReply = "已为您取消订单 10086，款项将原路退回。";
        TicketState st = state(graphReply, "CANCEL_ORDER",
                List.of(ToolResult.success("cancel_order", "{\"orderNo\":\"10086\",\"status\":\"CANCELLED\"}")));
        when(runner.resume(eq(CONV_ID), eq(true), eq(TICKET_ID), isNull()))
                .thenReturn(new RunOutcome(Reason.COMPLETED, st, null));

        TicketApplicationService service = buildService(runner, convRepo, ticketRepo);
        ActionConfirmResult result = service.confirmAction(CONV_ID, ACTION_ID, true);

        assertEquals("APPROVED", result.decision());
        assertEquals(graphReply, result.message(), "回复应取自图终态，而非固定文案");
        assertEquals("{\"orderNo\":\"10086\",\"status\":\"CANCELLED\"}", result.toolResult());
        assertEquals(TicketStatus.HANDLED, ticket.getStatus());
        assertEquals(graphReply, ticket.getResponse());

        // 挂起动作被消费、会话与工单均已持久化
        assertNull(conversation.getPendingAction(), "确认后应消费挂起动作");
        verify(runner).resume(eq(CONV_ID), eq(true), eq(TICKET_ID), isNull());
        verify(convRepo).save(conversation);
        verify(ticketRepo).save(ticket);
    }

    @Test
    @DisplayName("拒绝 - 委托 resume(false) 续跑，映射图的拒绝回复，不执行副作用")
    void reject_delegatesToResumeAndMapsOutcome() throws Exception {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        TicketGraphRunner runner = mock(TicketGraphRunner.class);

        Conversation conversation = conversationWithPendingAction();
        Ticket ticket = waitingTicket();
        when(convRepo.findById(CONV_ID)).thenReturn(conversation);
        when(ticketRepo.findById(TICKET_ID)).thenReturn(ticket);

        String graphReply = "好的，已为您取消该操作，未对订单做任何更改。";
        // 拒绝路径：图内 confirm 节点合成一条失败结果（用户拒绝），respond 生成诚实回复
        TicketState st = state(graphReply, "CANCEL_ORDER",
                List.of(ToolResult.failure("cancel_order", "用户拒绝执行该操作，未做任何更改")));
        when(runner.resume(eq(CONV_ID), eq(false), eq(TICKET_ID), isNull()))
                .thenReturn(new RunOutcome(Reason.COMPLETED, st, null));

        TicketApplicationService service = buildService(runner, convRepo, ticketRepo);
        ActionConfirmResult result = service.confirmAction(CONV_ID, ACTION_ID, false);

        assertEquals("REJECTED", result.decision());
        assertEquals(graphReply, result.message());
        assertEquals(TicketStatus.HANDLED, ticket.getStatus());
        verify(runner).resume(eq(CONV_ID), eq(false), eq(TICKET_ID), isNull());
    }

    @Test
    @DisplayName("续跑异常 - 降级为人工处理并返回失败文案")
    void resumeThrows_degradesToManualReview() throws Exception {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        TicketGraphRunner runner = mock(TicketGraphRunner.class);

        Conversation conversation = conversationWithPendingAction();
        Ticket ticket = waitingTicket();
        when(convRepo.findById(CONV_ID)).thenReturn(conversation);
        when(ticketRepo.findById(TICKET_ID)).thenReturn(ticket);
        when(runner.resume(eq(CONV_ID), eq(true), eq(TICKET_ID), isNull()))
                .thenThrow(new RuntimeException("graph boom"));

        TicketApplicationService service = buildService(runner, convRepo, ticketRepo);
        ActionConfirmResult result = service.confirmAction(CONV_ID, ACTION_ID, true);

        assertEquals("APPROVED", result.decision());
        assertTrue(result.message().contains("失败"), "应返回失败文案，实际=" + result.message());
        assertEquals(TicketStatus.MANUAL_REVIEW, ticket.getStatus(), "续跑失败应降级人工");
        verify(ticketRepo).save(ticket);
    }

    @Test
    @DisplayName("无挂起动作 - 直接拒绝确认请求，不触达图")
    void noPendingAction_throws() throws Exception {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        TicketGraphRunner runner = mock(TicketGraphRunner.class);

        Conversation conversation = Conversation.start(CONV_ID, "C001");
        conversation.beginTurn(TICKET_ID);
        when(convRepo.findById(CONV_ID)).thenReturn(conversation);

        TicketApplicationService service = buildService(runner, convRepo, ticketRepo);
        assertThrows(IllegalStateException.class,
                () -> service.confirmAction(CONV_ID, ACTION_ID, true));
        verify(runner, never()).resume(any(), anyBoolean(), any(), any());
    }
}
