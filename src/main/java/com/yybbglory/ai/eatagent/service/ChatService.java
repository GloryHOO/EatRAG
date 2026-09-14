package com.yybbglory.ai.eatagent.service;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.exception.BusinessException;
import com.yybbglory.ai.eatagent.model.dto.ChatResponse;
import com.yybbglory.ai.eatagent.model.dto.StreamMessage;
import com.yybbglory.ai.eatagent.model.enums.QueryRouteType;
import com.yybbglory.ai.eatagent.rag.document.ParentChildManager;
import com.yybbglory.ai.eatagent.rag.query.FilterExtractor;
import com.yybbglory.ai.eatagent.rag.query.QueryRewriter;
import com.yybbglory.ai.eatagent.rag.query.QueryRouter;
import com.yybbglory.ai.eatagent.rag.retrieval.HybridRetriever;
import com.yybbglory.ai.eatagent.util.PromptTemplateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 问答业务编排服务
 * 完整 pipeline: 路由 → 重写 → 检索 → 生成
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ParentChildManager parentChildManager;
    private final QueryRouter queryRouter;
    private final QueryRewriter queryRewriter;
    private final FilterExtractor filterExtractor;
    private final HybridRetriever hybridRetriever;
    private final RagProperties properties;

    // Prompt 模板路径
    private static final String BASIC_ANSWER_TEMPLATE = "prompts/basic-answer.st";
    private static final String STEP_BY_STEP_TEMPLATE = "prompts/step-by-step-answer.st";

    public ChatService(ChatModel chatModel,
                       KnowledgeService knowledgeService,
                       ParentChildManager parentChildManager,
                       QueryRouter queryRouter,
                       QueryRewriter queryRewriter,
                       FilterExtractor filterExtractor,
                       HybridRetriever hybridRetriever,
                       RagProperties properties) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
        this.parentChildManager = parentChildManager;
        this.queryRouter = queryRouter;
        this.queryRewriter = queryRewriter;
        this.filterExtractor = filterExtractor;
        this.hybridRetriever = hybridRetriever;
        this.properties = properties;
    }

    /**
     * 同步问答
     */
    public ChatResponse chat(String question) {
        if (!knowledgeService.isIndexReady()) {
            throw new BusinessException(503, "知识库索引尚未就绪，请稍后重试或通过管理 API 重建索引");
        }

        long startTime = System.currentTimeMillis();

        // 1. 查询路由
        QueryRouteType routeType = queryRouter.route(question);
        log.info("查询路由结果: '{}' → {}", question, routeType);

        // 2. 查询重写
        String rewrittenQuery = queryRewriter.rewrite(question, routeType);
        if (!rewrittenQuery.equals(question)) {
            log.info("查询重写: '{}' → '{}'", question, rewrittenQuery);
        }

        // 3. 提取过滤条件
        Map<String, String> filters = filterExtractor.extractFilters(question);
        if (!filters.isEmpty()) {
            log.info("过滤条件: {}", filters);
        }

        // 4. 混合检索
        List<Document> retrievedDocs = hybridRetriever.retrieve(rewrittenQuery, filters);
        log.info("检索到 {} 个相关文档块", retrievedDocs.size());

        // 5. 回溯父文档
        List<Document> parentDocs = parentChildManager.getParentDocuments(retrievedDocs);
        log.info("回溯到 {} 篇完整食谱", parentDocs.size());

        // 6. 生成回答
        String answer;
        if (routeType == QueryRouteType.LIST) {
            answer = generateListAnswer(parentDocs);
        } else {
            String context = buildContext(parentDocs);
            String templatePath = (routeType == QueryRouteType.DETAIL) ? STEP_BY_STEP_TEMPLATE : BASIC_ANSWER_TEMPLATE;
            String formattedPrompt = PromptTemplateUtils.render(templatePath, Map.of(
                    "question", question,
                    "context", context
            ));
            answer = chatModel.call(new Prompt(List.of(new UserMessage(formattedPrompt))))
                    .getResult().getOutput().getText();
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("问答完成: 耗时 {}ms, 路由={}", duration, routeType);

        // 构建来源信息
        List<ChatResponse.SourceInfo> sources = parentDocs.stream()
                .map(doc -> new ChatResponse.SourceInfo(
                        (String) doc.getMetadata().getOrDefault("parent_id", ""),
                        (String) doc.getMetadata().getOrDefault("dish_name", "未知"),
                        (String) doc.getMetadata().getOrDefault("category", "未知"),
                        (String) doc.getMetadata().getOrDefault("difficulty", "未知")
                ))
                .toList();

        return new ChatResponse(answer, routeType.name().toLowerCase(), sources);
    }

    /**
     * 流式问答
     */
    public Flux<StreamMessage> chatStream(String question) {
        if (!knowledgeService.isIndexReady()) {
            return Flux.just(StreamMessage.message("知识库索引尚未就绪，请稍后重试。"));
        }

        // 同步执行路由、重写、检索（这些步骤很快）
        QueryRouteType routeType = queryRouter.route(question);
        String rewrittenQuery = queryRewriter.rewrite(question, routeType);
        Map<String, String> filters = filterExtractor.extractFilters(question);
        List<Document> retrievedDocs = hybridRetriever.retrieve(rewrittenQuery, filters);
        List<Document> parentDocs = parentChildManager.getParentDocuments(retrievedDocs);

        List<ChatResponse.SourceInfo> sources = parentDocs.stream()
                .map(doc -> new ChatResponse.SourceInfo(
                        (String) doc.getMetadata().getOrDefault("parent_id", ""),
                        (String) doc.getMetadata().getOrDefault("dish_name", "未知"),
                        (String) doc.getMetadata().getOrDefault("category", "未知"),
                        (String) doc.getMetadata().getOrDefault("difficulty", "未知")
                ))
                .toList();

        if (routeType == QueryRouteType.LIST) {
            // 列表回答不需要 LLM 流式
            String answer = generateListAnswer(parentDocs);
            return Flux.just(
                    StreamMessage.message(answer),
                    StreamMessage.done(routeType.name().toLowerCase(), sources, question, rewrittenQuery)
            );
        }

        // 构建 prompt
        String context = buildContext(parentDocs);
        String templatePath = (routeType == QueryRouteType.DETAIL) ? STEP_BY_STEP_TEMPLATE : BASIC_ANSWER_TEMPLATE;
        String formattedPrompt = PromptTemplateUtils.render(templatePath, Map.of(
                "question", question,
                "context", context
        ));

        // 流式调用 LLM
        Flux<String> llmStream = chatModel.stream(new Prompt(List.of(new UserMessage(formattedPrompt))))
                .map(response -> response.getResult().getOutput().getText())
                .filter(Objects::nonNull);

        // 拼接 done 事件
        return llmStream
                .map(StreamMessage::message)
                .concatWith(Flux.just(StreamMessage.done(routeType.name().toLowerCase(), sources, question, rewrittenQuery)));
    }

    /**
     * 生成列表式回答
     */
    private String generateListAnswer(List<Document> parentDocs) {
        if (parentDocs.isEmpty()) {
            return "抱歉，没有找到相关的菜品信息。";
        }

        List<String> dishNames = parentDocs.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("dish_name", "未知菜品"))
                .distinct()
                .toList();

        if (dishNames.size() == 1) {
            return "为您推荐：" + dishNames.getFirst();
        } else if (dishNames.size() <= 3) {
            StringBuilder sb = new StringBuilder("为您推荐以下菜品：\n");
            for (int i = 0; i < dishNames.size(); i++) {
                sb.append(i + 1).append(". ").append(dishNames.get(i)).append("\n");
            }
            return sb.toString().trim();
        } else {
            StringBuilder sb = new StringBuilder("为您推荐以下菜品：\n");
            for (int i = 0; i < 3; i++) {
                sb.append(i + 1).append(". ").append(dishNames.get(i)).append("\n");
            }
            sb.append("\n还有其他 ").append(dishNames.size() - 3).append(" 道菜品可供选择。");
            return sb.toString();
        }
    }

    /**
     * 构建上下文字符串
     */
    private String buildContext(List<Document> docs) {
        int maxLength = properties.rag() != null ? properties.rag().contextMaxLength() : 2000;

        if (docs.isEmpty()) {
            return "暂无相关食谱信息。";
        }

        StringBuilder sb = new StringBuilder();
        int currentLength = 0;
        String divider = "\n" + "=".repeat(50) + "\n";

        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String dishName = (String) doc.getMetadata().getOrDefault("dish_name", "未知");
            String category = (String) doc.getMetadata().getOrDefault("category", "未知");
            String difficulty = (String) doc.getMetadata().getOrDefault("difficulty", "未知");

            String header = String.format("【食谱 %d】%s | 分类: %s | 难度: %s\n", i + 1, dishName, category, difficulty);
            String docText = header + doc.getText() + "\n";

            if (currentLength + docText.length() > maxLength) {
                break;
            }

            if (i > 0) {
                sb.append(divider);
            }
            sb.append(docText);
            currentLength += docText.length() + divider.length();
        }

        return divider + sb.toString();
    }
}
