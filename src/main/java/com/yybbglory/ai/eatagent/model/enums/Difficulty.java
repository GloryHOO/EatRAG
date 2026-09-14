package com.yybbglory.ai.eatagent.model.enums;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 难度等级枚举
 * 通过星号（★）数量映射到中文难度描述
 */
public enum Difficulty {

    VERY_EASY(1, "非常简单"),
    EASY(2, "简单"),
    MEDIUM(3, "中等"),
    HARD(4, "困难"),
    VERY_HARD(5, "非常困难"),
    UNKNOWN(0, "未知");

    private static final Pattern STAR_PATTERN = Pattern.compile("★+");

    /** 按长度降序排列的难度标签列表，用于过滤条件提取时避免误判 */
    public static final List<String> LABELS_BY_LENGTH_DESC = List.of(
            "非常困难", "非常简单", "困难", "中等", "简单"
    );

    private final int starCount;
    private final String label;

    Difficulty(int starCount, String label) {
        this.starCount = starCount;
        this.label = label;
    }

    public int getStarCount() {
        return starCount;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 从文本内容中解析难度等级
     * 查找连续 ★ 的数量并映射
     *
     * @param content Markdown 文件内容
     * @return 对应的难度等级，未找到返回 UNKNOWN
     */
    public static Difficulty fromContent(String content) {
        if (content == null || content.isEmpty()) {
            return UNKNOWN;
        }
        Matcher matcher = STAR_PATTERN.matcher(content);
        if (matcher.find()) {
            int count = matcher.group().length();
            return fromStarCount(count);
        }
        return UNKNOWN;
    }

    /**
     * 根据星号数量获取难度等级
     */
    public static Difficulty fromStarCount(int count) {
        for (Difficulty d : values()) {
            if (d.starCount == count) {
                return d;
            }
        }
        return UNKNOWN;
    }

    /**
     * 根据中文标签获取难度等级
     */
    public static Difficulty fromLabel(String label) {
        if (label == null) {
            return UNKNOWN;
        }
        for (Difficulty d : values()) {
            if (d.label.equals(label)) {
                return d;
            }
        }
        return UNKNOWN;
    }
}
