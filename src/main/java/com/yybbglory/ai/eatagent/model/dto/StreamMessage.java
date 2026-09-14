package com.yybbglory.ai.eatagent.model.dto;

import java.util.List;

/**
 * SSE 流式消息 DTO
 */
public record StreamMessage(
        /** 消息类型: "message" 或 "done" */
        String type,
        /** 文本内容片段（type=message 时有值） */
        String content,
        /** 查询路由类型（type=done 时有值） */
        String routeType,
        /** 引用来源（type=done 时有值） */
        List<ChatResponse.SourceInfo> sources,
        /** 原始查询（type=done 时有值，供前端展示重写对比） */
        String originalQuery,
        /** 重写后的查询（type=done 时有值） */
        String rewrittenQuery
) {
    /**
     * 创建文本片段消息
     */
    public static StreamMessage message(String content) {
        return new StreamMessage("message", content, null, null, null, null);
    }

    /**
     * 创建完成消息
     *
     * @param routeType      查询路由类型
     * @param sources        引用来源列表
     * @param originalQuery  原始查询
     * @param rewrittenQuery 重写后的查询
     */
    public static StreamMessage done(String routeType,
                                     List<ChatResponse.SourceInfo> sources,
                                     String originalQuery,
                                     String rewrittenQuery) {
        return new StreamMessage("done", null, routeType, sources, originalQuery, rewrittenQuery);
    }
}