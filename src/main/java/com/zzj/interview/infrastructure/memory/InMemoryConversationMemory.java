package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.memory.ConversationMemory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话记忆实现
 *
 * 简化版：使用ConcurrentHashMap存储
 * 生产环境建议：Redis + TTL过期策略
 */
@Component
public class InMemoryConversationMemory implements ConversationMemory {

    // sessionId -> 消息列表
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();

    // 每个会话最多保留的消息数
    private static final int MAX_MESSAGES_PER_SESSION = 50;

    @Override
    public void addMessage(String sessionId, Message message) {
        sessions.compute(sessionId, (k, messages) -> {
            if (messages == null) {
                messages = new ArrayList<>();
            }
            messages.add(message);
            // 超过限制时移除最早的消息
            while (messages.size() > MAX_MESSAGES_PER_SESSION) {
                messages.remove(0);
            }
            return messages;
        });
    }

    @Override
    public List<Message> getRecentMessages(String sessionId, int limit) {
        List<Message> messages = sessions.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, messages.size() - limit);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    @Override
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }
}
