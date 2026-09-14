package com.yybbglory.ai.eatagent.controller;

import com.yybbglory.ai.eatagent.model.ApiResponse;
import com.yybbglory.ai.eatagent.model.dto.ChatRequest;
import com.yybbglory.ai.eatagent.model.dto.ChatResponse;
import com.yybbglory.ai.eatagent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 问答 API 控制器
 * 提供同步问答和 SSE 流式问答端点
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 同步问答
     * POST /api/chat
     */
    @PostMapping
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        ChatResponse response = chatService.chat(request.question());
        return ApiResponse.success(response);
    }

    /**
     * SSE 流式问答
     * GET /api/chat/stream?question=xxx
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        SseEmitter emitter = new SseEmitter(120_000L); // 2分钟超时

        chatService.chatStream(question)
                .subscribe(
                        message -> {
                            try {
                                String json = JacksonUtils.getDefaultJsonMapper().writeValueAsString(message);
                                emitter.send(SseEmitter.event()
                                        .name(message.type())
                                        .data(json));
                            } catch (IOException e) {
                                log.warn("SSE 发送失败: {}", e.getMessage());
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.error("流式问答异常: {}", error.getMessage(), error);
                            emitter.completeWithError(error);
                        },
                        emitter::complete
                );

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("SSE 连接异常断开: {}", e.getMessage()));

        return emitter;
    }
}
