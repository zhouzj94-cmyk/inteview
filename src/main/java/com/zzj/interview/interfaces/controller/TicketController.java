package com.zzj.interview.interfaces.controller;

import com.zzj.interview.application.command.CreateTicketCommand;
import com.zzj.interview.application.service.TicketApplicationService;
import com.zzj.interview.interfaces.dto.CreateTicketRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 工单REST控制器（Interfaces层）
 *
 * 职责：
 * 1. 接收HTTP请求，将DTO转换为Command
 * 2. 调用ApplicationService编排业务流程
 * 3. 通过SSE（Server-Sent Events）向前端推送实时处理进度
 */
@RestController
@RequestMapping("/api/tickets")
@Tag(name = "工单管理", description = "AI智能客服工单相关接口")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    private final TicketApplicationService ticketApplicationService;

    public TicketController(TicketApplicationService ticketApplicationService) {
        this.ticketApplicationService = ticketApplicationService;
    }

    /**
     * 创建工单并通过SSE流式返回处理过程
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "创建工单（SSE流式）", description = "提交工单后通过SSE实时推送AI分析进度和结果")
    public SseEmitter createAndStream(@Valid @RequestBody CreateTicketRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        request.setCustomerId("C001");
        log.info("收到工单创建请求: customerId={}, conversationId={}",
                request.getCustomerId(), request.getConversationId());

        ticketApplicationService.createAndStream(
                new CreateTicketCommand(request.getCustomerId(), request.getContent(), request.getConversationId()),
                emitter
        );

        return emitter;
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务是否正常运行")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "ai-ticket-system");
    }
}
