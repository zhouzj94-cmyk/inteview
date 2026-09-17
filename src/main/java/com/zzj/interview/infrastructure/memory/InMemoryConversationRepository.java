package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.model.conversation.Conversation;
import com.zzj.interview.domain.repository.ConversationRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话仓储实现
 *
 * 用ConcurrentHashMap按conversationId保存会话状态，支撑多轮问答/澄清/确认。
 * 生产环境可替换为Redis（带TTL过期）；接口在Domain层，切换实现不影响业务。
 */
@Component
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<String, Conversation> store = new ConcurrentHashMap<>();

    @Override
    public void save(Conversation conversation) {
        if (conversation != null && conversation.getConversationId() != null) {
            store.put(conversation.getConversationId(), conversation);
        }
    }

    @Override
    public Conversation findById(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return store.get(conversationId);
    }
}
