package com.zzj.interview.domain.memory;

import java.util.Map;

/**
 * 用户记忆端口（长期记忆）
 *
 * 职责：
 * 1. 存储用户偏好、历史交互摘要
 * 2. 跨会话保持用户画像
 * 3. 为个性化服务提供依据
 *
 * 与短期记忆的区别：
 * - 短期记忆：当前会话，临时，用于上下文
 * - 长期记忆：持久化，跨会话，用于个性化
 */
public interface UserMemory {

    /**
     * 保存用户记忆
     */
    void save(String userId, UserMemoryData data);

    /**
     * 获取用户记忆
     */
    UserMemoryData get(String userId);

    /**
     * 更新用户偏好
     */
    void updatePreference(String userId, String key, String value);

    /**
     * 记录一次用户交互到语义长期记忆（向量库实现覆盖；默认无操作）
     *
     * @param userId    用户ID
     * @param sessionId 会话/工单ID
     * @param content   交互原文
     * @param intent    识别到的意图
     */
    default void recordInteraction(String userId, String sessionId, String content, String intent) {
        // 默认不持久化语义记忆（纯画像实现）
    }

    /**
     * 按语义相似度检索某用户的历史交互（向量库实现覆盖；默认返回空）
     */
    default java.util.List<Map<String, Object>> retrieveRelevant(String userId, String query, int topK) {
        return java.util.List.of();
    }

    /**
     * 用户记忆数据
     */
    record UserMemoryData(
            String userId,
            String preferredLanguage,
            String commonIntent,      // 最常咨询的问题类型
            Map<String, String> preferences,
            String lastSummary,       // 上次对话摘要
            int totalInteractions,
            long lastInteractionTime
    ) {
        public static UserMemoryData empty(String userId) {
            return new UserMemoryData(userId, "zh-CN", null,
                    Map.of(), null, 0, 0);
        }
    }
}
