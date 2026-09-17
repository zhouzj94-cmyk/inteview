package com.zzj.interview.infrastructure.ai;

import com.zzj.interview.domain.gateway.AiIntentRecognizer.ProcessContext;
import com.zzj.interview.domain.memory.ConversationMemory;
import com.zzj.interview.domain.model.ticket.TicketIntent;
import com.zzj.interview.infrastructure.memory.ConversationMemoryChatMemoryStore;
import com.zzj.interview.infrastructure.memory.InMemoryConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 识别器短期记忆写入测试
 *
 * 验证"记忆缺口"已修复：
 * 1. recognize 只写入"用户轮"，不写入意图JSON/工具调用（避免污染对话历史）
 * 2. generateResponse 写入"助手轮"——面向用户的自然语言回复
 * 3. 多轮下同一会话累积 user/assistant/user/assistant
 *
 * 用mock模式跑，无需真实模型；mock路径不触达模型/工具/RAG/监控依赖，故用null占位。
 */
@DisplayName("识别器短期记忆写入测试")
class RecognizerMemoryTest {

    private InMemoryConversationMemory conversationMemory;
    private LangChain4jIntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        conversationMemory = new InMemoryConversationMemory();
        ConversationMemoryChatMemoryStore store =
                new ConversationMemoryChatMemoryStore(conversationMemory);
        // 构造参数顺序：chatModel, toolAdapter, properties, promptManager,
        //               knowledgeBase, chatMemoryStore, userMemory, langfuseClient, objectMapper
        recognizer = new LangChain4jIntentRecognizer(
                null, null, null, null, null, store, null, null, null);
        ReflectionTestUtils.setField(recognizer, "mockEnabled", true);
    }

    @Test
    @DisplayName("recognize - 只写入用户轮")
    void recognize_recordsUserTurnOnly() {
        ProcessContext ctx = new ProcessContext("CV_mem1", "C001");
        recognizer.recognize("我想查订单10086", ctx);

        List<ConversationMemory.Message> history =
                conversationMemory.getRecentMessages("CV_mem1", 20);
        assertEquals(1, history.size(), "recognize后应只有1条（用户轮）");
        assertEquals("user", history.get(0).role());
        assertEquals("我想查订单10086", history.get(0).content());
    }

    @Test
    @DisplayName("generateResponse - 写入助手轮（自然语言回复）")
    void generateResponse_recordsAssistantTurn() {
        ProcessContext ctx = new ProcessContext("CV_mem2", "C001");
        recognizer.recognize("我想查订单10086", ctx);
        String reply = recognizer.generateResponse(
                "我想查订单10086", TicketIntent.ORDER_QUERY, List.of(), ctx);

        List<ConversationMemory.Message> history =
                conversationMemory.getRecentMessages("CV_mem2", 20);
        assertEquals(2, history.size(), "应有用户轮 + 助手轮");
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
        assertEquals(reply, history.get(1).content(), "助手轮内容应为面向用户的回复");
        assertFalse(history.get(1).content().isBlank(), "助手轮不应为空");
    }

    @Test
    @DisplayName("多轮 - 同一会话累积 user/assistant/user/assistant")
    void multiTurn_accumulatesAlternating() {
        ProcessContext ctx = new ProcessContext("CV_multi", "C001");

        recognizer.recognize("我想查订单10086", ctx);
        recognizer.generateResponse("我想查订单10086", TicketIntent.ORDER_QUERY, List.of(), ctx);
        recognizer.recognize("那它什么时候到", ctx);
        recognizer.generateResponse("那它什么时候到", TicketIntent.LOGISTICS, List.of(), ctx);

        List<ConversationMemory.Message> history =
                conversationMemory.getRecentMessages("CV_multi", 20);
        assertEquals(4, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
        assertEquals("user", history.get(2).role());
        assertEquals("assistant", history.get(3).role());
    }
}
