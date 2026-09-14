package com.yybbglory.ai.eatagent.model.dto;

/**
 * 问答请求 DTO
 */
public record ChatRequest(
        /** 用户问题 */
        String question
) {}
