package com.yybbglory.ai.eatagent.model.dto;

/**
 * 文档列表项（摘要信息）
 * 用于文档列表页的一行记录，不包含完整正文
 *
 * @param parentId   父文档唯一标识（相对路径 MD5）
 * @param dishName   菜名
 * @param category   分类
 * @param difficulty 难度
 * @param source     源文件路径
 */
public record DocumentSummary(
        String parentId,
        String dishName,
        String category,
        String difficulty,
        String source
) {}