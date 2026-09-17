package com.zzj.interview.infrastructure.ai;

import com.zzj.interview.domain.gateway.AiIntentRecognizer.ToolCall;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 识别器「文本JSON分支」兜底合成工具调用测试
 *
 * 背景：意图识别Prompt要求模型输出文本JSON，与Function Calling竞争，模型常"描述"出
 * 取消意图却不调用 cancel_order，导致 toolCalls 为空、敏感动作与人工确认永不触发。
 * 本测试锁定兜底逻辑：从文本/原文提取订单号并合成与Function Calling等价的工具调用。
 *
 * 另覆盖"我有哪些订单"场景：ORDER_QUERY 无订单号但有客户身份时合成 list_orders，
 * 而不是回落到追问订单号的澄清。
 */
@DisplayName("识别器文本分支兜底合成工具调用测试")
class RecognizerTextFallbackTest {

    @Test
    @DisplayName("订单号提取 - 优先取文本JSON里的orderNo字段")
    void extractOrderNo_prefersJsonField() {
        String text = "{\"intent\": \"CANCEL_ORDER\", \"reasoning\": \"用户要取消\", \"orderNo\": \"10086\"}";
        assertEquals("10086", LangChain4jIntentRecognizer.extractOrderNoFromText(text, "帮我取消订单"));
    }

    @Test
    @DisplayName("订单号提取 - 文本无orderNo时回退到用户原文(ORD形态/连续数字)")
    void extractOrderNo_fallsBackToContent() {
        assertEquals("ORD-2024-001",
                LangChain4jIntentRecognizer.extractOrderNoFromText("intent CANCEL_ORDER", "取消 ORD-2024-001"));
        assertEquals("10086",
                LangChain4jIntentRecognizer.extractOrderNoFromText("CANCEL_ORDER", "帮我取消订单10086"));
    }

    @Test
    @DisplayName("订单号提取 - 都提取不到返回null，绝不臆造默认订单号")
    void extractOrderNo_returnsNullWhenAbsent() {
        assertNull(LangChain4jIntentRecognizer.extractOrderNoFromText("CANCEL_ORDER", "帮我取消订单"));
        assertNull(LangChain4jIntentRecognizer.extractOrderNoFromText(null, null));
    }

    @Test
    @DisplayName("合成 - CANCEL_ORDER 生成 cancel_order(orderNo[,reason])")
    void synthesize_cancelOrder() {
        List<ToolCall> calls = LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.CANCEL_ORDER, "10086", "帮我取消订单10086", "C001");

        assertEquals(1, calls.size());
        assertEquals("cancel_order", calls.get(0).toolName());
        assertEquals("10086", calls.get(0).arguments().get("orderNo"));
        assertNotNull(calls.get(0).arguments().get("reason"));
    }

    @Test
    @DisplayName("合成 - ORDER_QUERY 带订单号 / LOGISTICS 各生成对应只读工具")
    void synthesize_queryAndLogistics() {
        List<ToolCall> q = LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.ORDER_QUERY, "10086", null, "C001");
        assertEquals(1, q.size());
        assertEquals("query_order", q.get(0).toolName());

        List<ToolCall> l = LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.LOGISTICS, "10086", null, "C001");
        assertEquals(1, l.size());
        assertEquals("query_logistics", l.get(0).toolName());
    }

    @Test
    @DisplayName("合成 - ORDER_QUERY 无订单号但有客户身份 → list_orders(customerId)")
    void synthesize_listOrdersWhenNoOrderNo() {
        List<ToolCall> calls = LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.ORDER_QUERY, null, "我有哪些订单", "C001");

        assertEquals(1, calls.size());
        assertEquals("list_orders", calls.get(0).toolName());
        assertEquals("C001", calls.get(0).arguments().get("customerId"));
    }

    @Test
    @DisplayName("合成 - ORDER_QUERY 既无订单号又无客户身份 → 空，交由澄清")
    void synthesize_orderQueryEmptyWithoutOrderNoAndCustomer() {
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.ORDER_QUERY, null, "我有哪些订单", null).isEmpty());
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.ORDER_QUERY, "  ", "我有哪些订单", "  ").isEmpty());
    }

    @Test
    @DisplayName("合成 - AFTER_SALE 先查订单再创建售后工单")
    void synthesize_afterSale() {
        List<ToolCall> calls = LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.AFTER_SALE, "10086", "我要退款", "C001");

        assertEquals(2, calls.size());
        assertEquals("query_order", calls.get(0).toolName());
        assertEquals("create_after_sale", calls.get(1).toolName());
        assertEquals("10086", calls.get(1).arguments().get("orderNo"));
    }

    @Test
    @DisplayName("合成 - 取消/物流/售后缺订单号即便有客户身份也返回空，交由澄清处理")
    void synthesize_emptyWhenNoOrderNoOrNonActionable() {
        // 只有 ORDER_QUERY 会回落到 list_orders；需订单号的动作类意图缺订单号仍为空
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.CANCEL_ORDER, null, "取消订单", "C001").isEmpty());
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.CANCEL_ORDER, "  ", "取消订单", "C001").isEmpty());
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.LOGISTICS, null, "我的快递到哪了", "C001").isEmpty());
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.UNKNOWN, "10086", "hi", "C001").isEmpty());
        assertTrue(LangChain4jIntentRecognizer.synthesizeToolCalls(
                TicketIntent.CLARIFY, "10086", "hi", "C001").isEmpty());
    }

    @Test
    @DisplayName("通用政策咨询判别 - 物流规则/退货政策/运费等→true(走知识库)，具体订单/动作→false")
    void isGeneralPolicyQuestion_distinguishesPolicyFromSpecificOrder() {
        // 通用政策/规则咨询：应由知识库作答，不追问订单号
        assertTrue(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("你们平台的物流规则是什么样的"));
        assertTrue(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("退货政策是什么"));
        assertTrue(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("运费怎么收"));

        // 指向用户某笔具体订单：需要订单号，不放行到知识库
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("我的快递到哪了"));
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("我这个订单怎么还没发货"));

        // 动作类诉求（无政策词）/ 带订单号 / 空输入：均非通用政策咨询
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("我要退款"));
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("取消订单10086"));
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion(null));
        assertFalse(LangChain4jIntentRecognizer.isGeneralPolicyQuestion("  "));
    }
}
