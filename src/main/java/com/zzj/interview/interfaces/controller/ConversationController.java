package com.zzj.interview.interfaces.controller;

import com.zzj.interview.application.result.ActionConfirmResult;
import com.zzj.interview.application.service.TicketApplicationService;
import com.zzj.interview.interfaces.dto.ActionConfirmRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 会话REST控制器（Interfaces层）
 *
 * 职责：承载多轮会话相关接口，当前核心是"人机交互确认点"——
 * 当AI要执行敏感操作（如取消订单）时，前端拿到 confirmation_required 事件后，
 * 通过本接口回传用户的批准/拒绝决策。
 */
@RestController
@RequestMapping("/api/conversations")
@Tag(name = "会话管理", description = "多轮会话与人机交互确认相关接口")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final TicketApplicationService ticketApplicationService;

    public ConversationController(TicketApplicationService ticketApplicationService) {
        this.ticketApplicationService = ticketApplicationService;
    }

    /**
     * 确认或拒绝一个挂起的敏感操作
     *
     * @param conversationId 会话ID
     * @param actionId       挂起动作ID（来自 confirmation_required 事件）
     * @param request        用户决策（approved=true/false）
     */
    @PostMapping("/{conversationId}/actions/{actionId}")
    @Operation(summary = "确认敏感操作", description = "人机交互：用户批准或拒绝AI挂起的敏感操作（如取消订单）")
    public ActionConfirmResult confirmAction(@PathVariable String conversationId,
                                             @PathVariable String actionId,
                                             @RequestBody ActionConfirmRequest request) {
        log.info("收到操作确认请求: conversationId={}, actionId={}, approved={}",
                conversationId, actionId, request.isApproved());
        return ticketApplicationService.confirmAction(conversationId, actionId, request.isApproved());
    }

    /**
     * 会话状态冲突（如：没有待确认操作）——仅在本控制器内转为409，避免污染全局异常语义
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("会话状态冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "INVALID_CONVERSATION_STATE",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}
