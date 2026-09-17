package com.zzj.interview.domain.memory;

import java.util.List;

/**
 * 会话记忆端口（短期记忆）
 *
 * 职责：
 * 1. 存储当前会话的对话历史
 * 2. 提供上下文给AI，实现多轮对话
 * 3. 会话结束后清理或归档
 *
 * Domain层定义接口，Infrastructure层选择存储方式（内存/Redis）
 */
public interface ConversationMemory {

    /**
     * 添加一条对话记录
     */
    void addMessage(String sessionId, Message message);

    /**
     * 获取会话历史（最近N条）
     */
    List<Message> getRecentMessages(String sessionId, int limit);

    /**
     * 清空会话记忆
     */
    void clear(String sessionId);

    /**
     * 消息记录
     */
    record Message(
            String role,      // "user" / "assistant" / "system"
            String content,
            long timestamp,
            String intent     // 识别到的意图（如有）
    ) {
        public static Message user(String content) {
            return new Message("user", content, System.currentTimeMillis(), null);
        }

        public static Message assistant(String content, String intent) {
            return new Message("assistant", content, System.currentTimeMillis(), intent);
        }
    }
}
