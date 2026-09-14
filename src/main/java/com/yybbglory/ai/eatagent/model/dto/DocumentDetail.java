package com.yybbglory.ai.eatagent.model.dto;

/**
 * 文档详情响应
 * 返回一份食谱的完整 Markdown 原文及元数据
 *
 * @param parentId   父文档唯一标识
 * @param dishName   菜名
 * @param category   分类
 * @param difficulty 难度
 * @param source     源文件路径
 * @param content    完整 Markdown 原文
 */
public record DocumentDetail(
        String parentId,
        String dishName,
        String category,
        String difficulty,
        String source,
        String content
) {}