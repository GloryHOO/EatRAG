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

/**
 * 查询重写器
 * <p>
 * 根据路由类型有条件地调用 LLM 对用户查询进行重写，
 * 以提高食谱检索的召回率和精确度。
 * <ul>
 *   <li>LIST 类型：直接返回原始查询（列表查询本身已足够明确）</li>
 *   <li>DETAIL / GENERAL 类型：调用 LLM 判断是否需要重写</li>
 * </ul>
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    /** 提示词模板路径 */
    private static final String PROMPT_TEMPLATE_PATH = "prompts/query-rewrite.st";

    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public QueryRewriter(ChatModel chatModel, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    /**
     * 根据路由类型决定是否重写用户查询
     *
     * @param query     用户原始查询
     * @param routeType 查询路由类型
     * @return 重写后的查询（或原始查询）
     */
    public String rewrite(String query, QueryRouteType routeType) {
        if (query == null || query.isBlank()) {
            return query;
        }

        // LIST 类型无需重写，直接返回原查询，节省一次 API 调用
        if (routeType == QueryRouteType.LIST) {
            log.debug("LIST 类型查询跳过重写: '{}'", query);
            return query;
        }

        // DETAIL 和 GENERAL 类型调用 LLM 进行重写
        return rewriteByLlm(query);
    }

    /**
     * 通过 LLM 对查询进行重写
     */
    private String rewriteByLlm(String query) {
        try {
            // 使用 .st 模板渲染（{{query}} 占位符）
            String formattedPrompt = PromptTemplateUtils.render(PROMPT_TEMPLATE_PATH, Map.of("query", query));

            // 调用 ChatModel
            ChatResponse response = chatModel.call(
                    new Prompt(List.of(new UserMessage(formattedPrompt)))
            );

            // 提取重写后的查询
            String rewritten = response.getResult().getOutput().getText();
            if (rewritten != null && !rewritten.isBlank()) {
                String result = rewritten.trim();
                log.debug("查询重写: '{}' → '{}'", query, result);
                return result;
            }

            log.warn("LLM 重写返回空结果，使用原始查询: '{}'", query);
            return query;

        } catch (Exception e) {
            log.error("查询重写 LLM 调用失败，降级使用原始查询: {}", e.getMessage(), e);
            return query;
        }
    }
}
