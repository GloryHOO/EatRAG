package com.yybbglory.ai.eatagent.model.enums;

/**
 * 查询路由类型枚举
 * 根据用户问题意图分类，决定不同的回答生成策略
 */
public enum QueryRouteType {

    /** 列表查询：用户想要菜品推荐或列表 */
    LIST,

    /** 详细查询：用户想要具体制作方法 */
    DETAIL,

    /** 一般查询：其他烹饪相关问题 */
    GENERAL;

    /**
     * 从字符串解析路由类型，解析失败默认返回 GENERAL
     */
    public static QueryRouteType fromString(String value) {
        if (value == null) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}
