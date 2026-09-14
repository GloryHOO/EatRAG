package com.yybbglory.ai.eatagent.rag.query;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.model.enums.QueryRouteType;
import com.yybbglory.ai.eatagent.util.PromptTemplateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 混合查询路由器
 * <p>
 * 采用两层策略判断用户查询意图：
 * <ol>
 *   <li>规则层：正则匹配列表类查询，零 API 调用</li>
 *   <li>LLM 层：规则未命中时调用大模型分类</li>
 * </ol>
 */
@Component
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    /** 提示词模板路径 */
    private static final String PROMPT_TEMPLATE_PATH = "prompts/query-router.st";

    /** 列表查询正则模式集合（任一匹配即判定为 LIST） */
    private static final List<Pattern> LIST_PATTERNS = List.of(
            Pattern.compile("推荐.*[菜道个]"),
            Pattern.compile("有什么.*[菜]?"),
            Pattern.compile("给我.*[菜道个]"),
            Pattern.compile("列出"),
            Pattern.compile("哪些"),
            Pattern.compile("有哪些"),
            Pattern.compile("想找"),
            Pattern.compile("有没有.*[菜推]")
    );

    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public QueryRouter(ChatModel chatModel, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * 对用户查询进行路由分类
     *
     * @param query 用户原始查询
     * @return 查询路由类型（LIST / DETAIL / GENERAL）
     */
    public QueryRouteType route(String query) {
        if (query == null || query.isBlank()) {
            return QueryRouteType.GENERAL;
        }

        // 第一层：规则匹配，零 API 开销
        for (Pattern pattern : LIST_PATTERNS) {
            if (pattern.matcher(query).find()) {
                log.debug("查询 '{}' 命中列表规则: {}", query, pattern.pattern());
                return QueryRouteType.LIST;
            }
        }

        // 第二层：LLM 分类
        return routeByLlm(query);
    }

    /**
     * 通过 LLM 对查询进行分类
     */
    private QueryRouteType routeByLlm(String query) {
        try {
            // 使用 .st 模板渲染（{{query}} 占位符）
            String formattedPrompt = PromptTemplateUtils.render(PROMPT_TEMPLATE_PATH, Map.of("query", query));

            // 调用 ChatModel
            ChatResponse response = chatModel.call(
                    new Prompt(List.of(new UserMessage(formattedPrompt)))
            );

            // 解析 LLM 返回的分类结果
            String content = response.getResult().getOutput().getText();
            if (content != null) {
                String trimmed = content.trim().toLowerCase();
                log.debug("LLM 路由结果: '{}' → '{}'", query, trimmed);

                // 从返回文本中提取分类关键词
                if (trimmed.contains("list")) {
                    return QueryRouteType.LIST;
                } else if (trimmed.contains("detail")) {
                    return QueryRouteType.DETAIL;
                } else if (trimmed.contains("general")) {
                    return QueryRouteType.GENERAL;
                }
            }

            log.warn("LLM 路由返回无法解析的内容: '{}'，默认使用 GENERAL", content);
            return QueryRouteType.GENERAL;

        } catch (Exception e) {
            log.error("LLM 路由调用失败，降级为 GENERAL: {}", e.getMessage(), e);
            return QueryRouteType.GENERAL;
        }
    }
}
