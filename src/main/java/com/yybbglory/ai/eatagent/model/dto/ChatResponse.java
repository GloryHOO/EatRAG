package com.yybbglory.ai.eatagent.model.dto;

import java.util.List;

/**
 * 问答响应 DTO
 */
public record ChatResponse(
        /** 生成的回答 */
        String answer,
        /** 查询路由类型 */
        String routeType,
        /** 引用的食谱来源列表 */
        List<SourceInfo> sources
) {
    /**
     * 食谱来源信息
     */
    public record SourceInfo(
            /** 父文档唯一标识，用于跳转详情 */
            String parentId,
            String dishName,
            String category,
            String difficulty
    ) {}
}
