package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.memory.ConversationMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j ChatMemoryStore桥接器
 *
 * 把领域层的ConversationMemory端口适配成LangChain4j的ChatMemoryStore，
 * 使短期记忆成为LangChain4j原生的ChatMemory，可直接配合
 * MessageWindowChatMemory / AiServices使用。
 *
 * 六边形架构体现：
 * - ConversationMemory是Domain定义的端口（不依赖AI框架）
 * - 本类是Infrastructure的适配器，引入LangChain4j的ChatMemoryStore技术细节
 * - 领域记忆和LangChain4j记忆共享同一份底层存储，避免双写不一致
 *
 * 面试话术：
 * "短期记忆用LangChain4j的ChatMemory抽象，底层存储通过ChatMemoryStore
 *  桥接到领域端口，这样既能用框架的滑动窗口能力，又不让领域层绑定框架。"
 */
@Component
public class ConversationMemoryChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryChatMemoryStore.class);

    /** 桥接读取时一次性取出的最大历史条数 */
    private static final int READ_WINDOW = 100;

    private final ConversationMemory conversationMemory;

    public ConversationMemoryChatMemoryStore(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = String.valueOf(memoryId);
        List<ConversationMemory.Message> domainMessages =
                conversationMemory.getRecentMessages(sessionId, READ_WINDOW);
        List<ChatMessage> messages = new ArrayList<>(domainMessages.size());
        for (ConversationMemory.Message m : domainMessages) {
            ChatMessage converted = toChatMessage(m);
            if (converted != null) {
                messages.add(converted);
            }
        }
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sessionId = String.valueOf(memoryId);
        // 领域端口只提供append+clear，这里用"清空后整体重写"实现滑动窗口语义
        conversationMemory.clear(sessionId);
        for (ChatMessage message : messages) {
            ConversationMemory.Message converted = toDomainMessage(message);
            if (converted != null) {
                conversationMemory.addMessage(sessionId, converted);
            }
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        conversationMemory.clear(String.valueOf(memoryId));
    }

    /**
     * 基于本Store创建一个LangChain4j滑动窗口记忆
     *
     * @param sessionId   会话ID（记忆隔离键）
     * @param maxMessages 窗口大小（保留最近N条消息）
     */
    public ChatMemory windowMemory(String sessionId, int maxMessages) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(maxMessages)
                .chatMemoryStore(this)
                .build();
    }

    // ==================== 领域Message ↔ LangChain4j ChatMessage 转换 ====================

    private ChatMessage toChatMessage(ConversationMemory.Message m) {
        if (m == null || m.content() == null) {
            return null;
        }
        return switch (m.role()) {
            case "system" -> SystemMessage.from(m.content());
            case "assistant" -> AiMessage.from(m.content());
            default -> UserMessage.from(m.content());
        };
    }

    private ConversationMemory.Message toDomainMessage(ChatMessage message) {
        if (message == null) {
            return null;
        }
        String text = message.text();
        if (message instanceof SystemMessage) {
            return new ConversationMemory.Message("system", text, System.currentTimeMillis(), null);
        }
        if (message instanceof AiMessage) {
            return ConversationMemory.Message.assistant(text, null);
        }
        if (message instanceof ToolExecutionResultMessage) {
            return new ConversationMemory.Message(
                    "system", "[tool_result] " + text, System.currentTimeMillis(), null);
        }
        // 其余类型（含UserMessage）统一按用户消息处理
        return ConversationMemory.Message.user(text);
    }
}
