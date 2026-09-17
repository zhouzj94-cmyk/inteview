package com.zzj.interview.infrastructure.memory;

import com.zzj.interview.domain.memory.UserMemory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存用户记忆实现
 *
 * 简化版：使用ConcurrentHashMap存储
 * 生产环境建议：MySQL/Redis持久化
 */
@Component
public class InMemoryUserMemory implements UserMemory {

    private final Map<String, UserMemoryData> users = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, UserMemoryData data) {
        users.put(userId, data);
    }

    @Override
    public UserMemoryData get(String userId) {
        return users.getOrDefault(userId, UserMemoryData.empty(userId));
    }

    @Override
    public void updatePreference(String userId, String key, String value) {
        users.compute(userId, (k, data) -> {
            if (data == null) {
                data = UserMemoryData.empty(userId);
            }
            Map<String, String> newPrefs = new ConcurrentHashMap<>(data.preferences());
            newPrefs.put(key, value);
            return new UserMemoryData(
                    data.userId(),
                    data.preferredLanguage(),
                    data.commonIntent(),
                    newPrefs,
                    data.lastSummary(),
                    data.totalInteractions(),
                    data.lastInteractionTime()
            );
        });
    }
}
