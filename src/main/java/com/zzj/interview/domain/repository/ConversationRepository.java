package com.zzj.interview.domain.repository;

import com.zzj.interview.domain.model.conversation.Conversation;

/**
 * 会话仓储端口（Domain层定义）
 *
 * 多轮对话需要在多次HTTP请求之间保留会话状态，由此端口抽象其存储。
 * Infrastructure层提供实现（内存/Redis），Domain不关心存储细节。
 */
public interface ConversationRepository {

    /**
     * 保存或更新会话
     */
    void save(Conversation conversation);

    /**
     * 按会话ID查询，不存在返回null
     */
    Conversation findById(String conversationId);
}
