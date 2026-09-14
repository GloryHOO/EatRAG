package com.yybbglory.ai.eatagent.model.dto;

import java.util.Map;

/**
 * 知识库统计信息响应 DTO
 */
public record KnowledgeStatsResponse(
        /** 文档总数 */
        int totalDocuments,
        /** 文本块总数 */
        int totalChunks,
        /** 分类分布 */
        Map<String, Integer> categories,
        /** 难度分布 */
        Map<String, Integer> difficulties,
        /** 索引是否就绪 */
        boolean indexReady
) {}
