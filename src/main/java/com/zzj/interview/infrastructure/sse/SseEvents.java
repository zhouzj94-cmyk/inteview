package com.zzj.interview.infrastructure.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * SSE 事件发送工具
 *
 * 统一"对象→JSON→SseEmitter.send"的封装，供编排层（processAsync）与
 * 图驱动层（TicketGraphRunner）复用，避免重复代码。
 * emitter 为 null 时静默跳过（如确认接口同步恢复图，不需要进度推送）。
 */
public final class SseEvents {

    private static final Logger log = LoggerFactory.getLogger(SseEvents.class);

    private SseEvents() {
    }

    public static void send(SseEmitter emitter, ObjectMapper objectMapper, String eventName, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.warn("SSE发送失败，客户端可能已断开: event={}", eventName);
        } catch (Exception e) {
            log.warn("SSE发送异常: event={}, err={}", eventName, e.getMessage());
        }
    }
}
