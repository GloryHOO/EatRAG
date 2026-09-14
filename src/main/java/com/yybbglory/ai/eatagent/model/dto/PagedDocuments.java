package com.yybbglory.ai.eatagent.model.dto;

import java.util.List;

/**
 * 分页文档列表响应
 *
 * @param total 文档总数（满足筛选条件）
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 * @param items 当前页的文档摘要列表
 */
public record PagedDocuments(
        long total,
        int page,
        int size,
        List<DocumentSummary> items
) {}