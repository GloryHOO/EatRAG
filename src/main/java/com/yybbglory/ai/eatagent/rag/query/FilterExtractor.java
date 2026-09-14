package com.yybbglory.ai.eatagent.rag.query;

import com.yybbglory.ai.eatagent.model.enums.Category;
import com.yybbglory.ai.eatagent.model.enums.Difficulty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过滤条件提取器
 * <p>
 * 通过正则/关键词匹配从用户查询中提取元数据过滤条件。
 * 纯规则匹配，不调用 LLM，零 API 开销。
 */
@Component
public class FilterExtractor {

    /** 所有分类中文标签（初始化时缓存） */
    private final List<String> categoryLabels;

    /** 按长度降序排列的难度标签（避免短标签误匹配长标签） */
    private final List<String> difficultyLabels;

    public FilterExtractor() {
        this.categoryLabels = Category.getAllLabels();
        this.difficultyLabels = Difficulty.LABELS_BY_LENGTH_DESC;
    }

    /**
     * 从用户查询中提取过滤条件
     *
     * @param query 用户原始查询
     * @return 包含 "category" 和/或 "difficulty" 的映射，未匹配到则返回空 Map
     */
    public Map<String, String> extractFilters(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }

        Map<String, String> filters = new LinkedHashMap<>();

        // 提取分类过滤条件：遍历所有分类标签，子串匹配即命中
        for (String label : categoryLabels) {
            if (query.contains(label)) {
                filters.put("category", label);
                break; // 只取第一个匹配的分类
            }
        }

        // 提取难度过滤条件：按长度降序匹配，避免"非常困难"被误判为"困难"
        for (String label : difficultyLabels) {
            if (query.contains(label)) {
                filters.put("difficulty", label);
                break; // 只取第一个匹配的难度
            }
        }

        return filters;
    }
}
