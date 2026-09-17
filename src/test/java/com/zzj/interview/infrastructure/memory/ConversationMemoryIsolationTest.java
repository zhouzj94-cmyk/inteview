package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.memory.ConversationMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话隔离测试
 *
 * 验证"哪些消息属于同一个会话"这一核心机制：
 * 短期记忆以 sessionId(=conversationId) 为键分区。
 * - 相同 sessionId → 多轮消息累积（多轮问答/ query改写的前提）
 * - 不同 sessionId → 完全隔离，互不串台
 *
 * 同时复现前端bug：若每一轮都换一个新的 sessionId（因为前端没有回传
 * conversationId，后端每次都新建会话），则历史永远为空，多轮对话失效。
 */
@DisplayName("会话隔离与多轮记忆测试")
class ConversationMemoryIsolationTest {

    private InMemoryConversationMemory conversationMemory;
    private ConversationMemoryChatMemoryStore store;

    @BeforeEach
    void setUp() {
        conversationMemory = new InMemoryConversationMemory();
        store = new ConversationMemoryChatMemoryStore(conversationMemory);
    }

    @Test
    @DisplayName("相同sessionId - 多轮消息累积")
    void sameSession_accumulates() {
        String cid = "CV_same";
        addUserTurn(cid, "我想查订单");
        addUserTurn(cid, "订单号是10086");
        addUserTurn(cid, "那它什么时候到");

        List<ConversationMemory.Message> history =
                conversationMemory.getRecentMessages(cid, 20);
        assertEquals(3, history.size(), "同一会话应累积3轮用户消息");
        assertEquals("我想查订单", history.get(0).content());
        assertEquals("那它什么时候到", history.get(2).content());
    }

    @Test
    @DisplayName("不同sessionId - 记忆完全隔离")
    void differentSession_isolated() {
        addUserTurn("CV_a", "会话A的消息");
        addUserTurn("CV_b", "会话B的消息");

        List<ConversationMemory.Message> a = conversationMemory.getRecentMessages("CV_a", 20);
        List<ConversationMemory.Message> b = conversationMemory.getRecentMessages("CV_b", 20);

        assertEquals(1, a.size());
        assertEquals(1, b.size());
        assertEquals("会话A的消息", a.get(0).content());
        assertEquals("会话B的消息", b.get(0).content());
    }

    @Test
    @DisplayName("复现前端bug - 每轮换sessionId则历史永远为空")
    void freshSessionEachTurn_losesHistory() {
        // 模拟前端不回传conversationId：后端每轮都生成新会话
        for (int turn = 1; turn <= 3; turn++) {
            String brandNewCid = "CV_" + java.util.UUID.randomUUID();
            // 本轮开始时读取历史（query改写/多轮依赖它）
            ChatMemory memory = store.windowMemory(brandNewCid, 20);
            List<ChatMessage> historyBeforeThisTurn = memory.messages();
            assertTrue(historyBeforeThisTurn.isEmpty(),
                    "第" + turn + "轮：新会话历史应为空 → 多轮上下文丢失");
            memory.add(UserMessage.from("第" + turn + "轮消息"));
        }
    }

    @Test
    @DisplayName("修复后 - 复用同一conversationId则能看到历史")
    void reuseConversationId_seesHistory() {
        String cid = "CV_stable";
        // 第1轮
        ChatMemory m1 = store.windowMemory(cid, 20);
        assertTrue(m1.messages().isEmpty(), "首轮无历史");
        m1.add(UserMessage.from("我想查订单"));

        // 第2轮：复用同一会话 → 能看到第1轮
        ChatMemory m2 = store.windowMemory(cid, 20);
        List<ChatMessage> history = m2.messages();
        assertEquals(1, history.size(), "第2轮应看到第1轮的历史");
    }

    private void addUserTurn(String sessionId, String text) {
        ChatMemory memory = store.windowMemory(sessionId, 20);
        memory.add(UserMessage.from(text));
    }
}
