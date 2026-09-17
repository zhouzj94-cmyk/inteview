package com.zzj.interview.domain.model.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket聚合根单元测试
 *
 * 测试要点：
 * 1. 工厂方法的业务规则校验
 * 2. 状态流转的业务不变量
 * 3. 边界条件处理
 */
@DisplayName("Ticket聚合根测试")
class TicketTest {

    @Test
    @DisplayName("创建工单 - 正常情况")
    void createTicket_success() {
        Ticket ticket = Ticket.create("T001", "C001", "我想查询订单状态");

        assertNotNull(ticket);
        assertEquals("T001", ticket.getId());
        assertEquals("C001", ticket.getCustomerId());
        assertEquals("我想查询订单状态", ticket.getContent());
        assertEquals(TicketIntent.UNKNOWN, ticket.getIntent());
        assertEquals(TicketStatus.CREATED, ticket.getStatus());
        assertNotNull(ticket.getCreatedAt());
    }

    @Test
    @DisplayName("创建工单 - 客户ID为空应抛异常")
    void createTicket_emptyCustomerId_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            Ticket.create("T001", "", "内容");
        });
    }

    @Test
    @DisplayName("创建工单 - 内容为空应抛异常")
    void createTicket_emptyContent_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            Ticket.create("T001", "C001", "  ");
        });
    }

    @Test
    @DisplayName("绑定意图 - CREATED状态可以绑定")
    void bindIntent_fromCreated_success() {
        Ticket ticket = Ticket.create("T001", "C001", "内容");
        ticket.bindIntent(TicketIntent.ORDER_QUERY);

        assertEquals(TicketIntent.ORDER_QUERY, ticket.getIntent());
        assertEquals(TicketStatus.ANALYZING, ticket.getStatus());
    }

    @Test
    @DisplayName("绑定意图 - 非CREATED状态应抛异常")
    void bindIntent_notCreated_throwsException() {
        Ticket ticket = Ticket.create("T001", "C001", "内容");
        ticket.bindIntent(TicketIntent.ORDER_QUERY);

        assertThrows(IllegalStateException.class, () -> {
            ticket.bindIntent(TicketIntent.LOGISTICS);
        });
    }

    @Test
    @DisplayName("标记为已处理")
    void markHandled_success() {
        Ticket ticket = Ticket.create("T001", "C001", "内容");
        ticket.markHandled("您的订单已发货");

        assertEquals(TicketStatus.HANDLED, ticket.getStatus());
        assertEquals("您的订单已发货", ticket.getResponse());
    }

    @Test
    @DisplayName("标记为待人工处理")
    void markManualReview_success() {
        Ticket ticket = Ticket.create("T001", "C001", "内容");
        ticket.markManualReview();

        assertEquals(TicketStatus.MANUAL_REVIEW, ticket.getStatus());
    }

    @Test
    @DisplayName("标记为处理失败")
    void markFailed_success() {
        Ticket ticket = Ticket.create("T001", "C001", "内容");
        ticket.markFailed("AI调用超时");

        assertEquals(TicketStatus.FAILED, ticket.getStatus());
        assertEquals("AI调用超时", ticket.getResponse());
    }
}
