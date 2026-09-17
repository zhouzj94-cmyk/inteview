package com.zzj.interview.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 人机交互确认请求DTO
 *
 * 当AI要执行敏感操作（如取消订单）时，会通过SSE推送 confirmation_required 事件，
 * 前端展示确认/取消按钮，用户点击后调用确认接口回传本请求。
 */
@Getter
@Setter
public class ActionConfirmRequest {

    /**
     * 用户是否批准该操作。true=批准执行，false=拒绝（不产生任何副作用）
     */
    private boolean approved;
}
