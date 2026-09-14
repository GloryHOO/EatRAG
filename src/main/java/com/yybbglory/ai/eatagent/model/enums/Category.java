package com.yybbglory.ai.eatagent.model.enums;

import java.util.Map;

/**
 * 食谱分类枚举
 * 映射文件系统目录名到中文分类名称
 */
public enum Category {

    MEAT_DISH("meat_dish", "荤菜"),
    VEGETABLE_DISH("vegetable_dish", "素菜"),
    SOUP("soup", "汤品"),
    DESSERT("dessert", "甜品"),
    BREAKFAST("breakfast", "早餐"),
    STAPLE("staple", "主食"),
    AQUATIC("aquatic", "水产"),
    CONDIMENT("condiment", "调料"),
    DRINK("drink", "饮品"),
    SEMI_FINISHED("semi-finished", "半成品"),
    TEMPLATE("template", "模板"),
    OTHER("other", "其他");

    private final String dirName;
    private final String label;

    /** 目录名 → 枚举 的查找缓存 */
    private static final Map<String, Category> DIR_NAME_MAP;

    static {
        DIR_NAME_MAP = Map.ofEntries(
                java.util.Arrays.stream(values())
                        .map(c -> Map.entry(c.dirName, c))
                        .toArray(Map.Entry[]::new)
        );
    }

    Category(String dirName, String label) {
        this.dirName = dirName;
        this.label = label;
    }

    public String getDirName() {
        return dirName;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据目录名查找分类，未找到返回 OTHER
     */
    public static Category fromDirName(String dirName) {
        return DIR_NAME_MAP.getOrDefault(dirName, OTHER);
    }

    /**
     * 获取所有中文分类标签列表（不含 OTHER）
     */
    public static java.util.List<String> getAllLabels() {
        return java.util.Arrays.stream(values())
                .filter(c -> c != OTHER)
                .map(Category::getLabel)
                .toList();
    }
}
