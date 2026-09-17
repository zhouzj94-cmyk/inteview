package com.zzj.interview.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建工单请求DTO
 * Interfaces层的数据结构，与Domain层解耦
 */
@Getter
@Setter
public class CreateTicketRequest {

    @NotBlank(message = "客户ID不能为空")
    private String customerId;

    @NotBlank(message = "工单内容不能为空")
    @Size(max = 2000, message = "工单内容不能超过2000字")
    private String content;

    /**
     * 会话ID（多轮对话）。首轮可不传，由后端生成并在响应事件中回传；
     * 后续轮次带上同一conversationId即可续接上下文。
     */
    @Size(max = 64, message = "会话ID长度不能超过64")
    private String conversationId;
}
