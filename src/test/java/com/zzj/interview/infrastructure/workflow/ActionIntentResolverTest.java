package com.zzj.interview.infrastructure.workflow;

import com.zzj.interview.domain.model.ticket.TicketIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ActionIntentResolver 单元测试
 *
 * 验证"意图弱化为派生标签"：意图由所选业务工具反向派生，
 * 工具无映射或无工具时回退到 AI 意图，最终兜底 UNKNOWN。
 */
@DisplayName("ActionIntentResolver测试")
class ActionIntentResolverTest {

    private final ActionIntentResolver resolver = new ActionIntentResolver();

    @Test
    @DisplayName("由工具派生权威意图（覆盖 AI 意图）")
    void resolve_derivesIntentFromTool() {
        assertEquals(TicketIntent.CANCEL_ORDER,
                resolver.resolve(List.of("cancel_order"), TicketIntent.UNKNOWN));
        assertEquals(TicketIntent.ORDER_QUERY,
                resolver.resolve(List.of("query_order"), TicketIntent.AFTER_SALE));
        assertEquals(TicketIntent.LOGISTICS,
                resolver.resolveOne("query_logistics", TicketIntent.UNKNOWN));
        assertEquals(TicketIntent.AFTER_SALE,
                resolver.resolveOne("create_after_sale", TicketIntent.UNKNOWN));
    }

    @Test
    @DisplayName("取第一个可映射的业务工具")
    void resolve_picksFirstMappableTool() {
        // run_code 无意图映射，应跳过它取后面的 cancel_order
        assertEquals(TicketIntent.CANCEL_ORDER,
                resolver.resolve(List.of("run_code", "cancel_order"), TicketIntent.UNKNOWN));
    }

    @Test
    @DisplayName("无映射工具时回退到 AI 意图")
    void resolve_fallsBackToAiIntentWhenNoMapping() {
        assertEquals(TicketIntent.CLARIFY,
                resolver.resolve(List.of("run_code"), TicketIntent.CLARIFY));
        assertEquals(TicketIntent.ORDER_QUERY,
                resolver.resolve(List.of(), TicketIntent.ORDER_QUERY));
        assertEquals(TicketIntent.AFTER_SALE,
                resolver.resolve(null, TicketIntent.AFTER_SALE));
    }

    @Test
    @DisplayName("既无映射工具又无 AI 意图时兜底 UNKNOWN")
    void resolve_defaultsToUnknown() {
        assertEquals(TicketIntent.UNKNOWN, resolver.resolve(List.of("run_code"), null));
        assertEquals(TicketIntent.UNKNOWN, resolver.resolveOne(null, null));
    }
}
