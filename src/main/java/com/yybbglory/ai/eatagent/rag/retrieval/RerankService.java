package com.yybbglory.ai.eatagent.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yybbglory.ai.eatagent.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Rerank 服务封装
 * 通过 HTTP 直接调用 DashScope Rerank API（非 OpenAI 兼容格式）
 * 支持超时降级：API 不可用时返回原始结果
 */
@Component
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RagProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankService(RagProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        int timeoutMs = properties.rag() != null ? properties.rag().rerankTimeoutMs() : 3000;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * 对候选文档列表进行重排序
     * 如果 Rerank API 调用失败或超时，降级返回原始列表
     *
     * @param query      用户查询
     * @param candidates 候选文档列表
     * @return 重排序后的文档列表（按相关性降序）
     */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        try {
            return doRerank(query, candidates);
        } catch (Exception e) {
            log.warn("Rerank API 调用失败，降级为原始排序: {}", e.getMessage());
            return candidates;
        }
    }

    /**
     * 执行实际的 Rerank API 调用
     */
    private List<Document> doRerank(String query, List<Document> candidates) throws Exception {
        RagProperties.RerankConfig rerankConfig = properties.rerank();
        if (rerankConfig == null || rerankConfig.apiKey() == null || rerankConfig.apiKey().isBlank()) {
            log.warn("Rerank API Key 未配置，跳过重排序");
            return candidates;
        }

        // 构建请求体（DashScope Rerank 原生格式：query/documents 必须嵌套在 input 下）
        List<String> documents = candidates.stream()
                .map(Document::getText)
                .toList();

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", false);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankConfig.model());
        requestBody.put("input", input);
        requestBody.put("parameters", parameters);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 发送 HTTP 请求
        int timeoutMs = properties.rag() != null ? properties.rag().rerankTimeoutMs() : 3000;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rerankConfig.baseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + rerankConfig.apiKey())
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Rerank API 返回状态码: " + response.statusCode() + ", body: " + response.body());
        }

        // 解析响应：DashScope 原生返回扁平的 results 数组，部分封装可能嵌套在 output 下
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            // 兼容 output.results 嵌套格式
            results = root.path("output").path("results");
        }

        if (!results.isArray()) {
            log.warn("Rerank API 响应格式异常，降级为原始排序: {}", response.body());
            return candidates;
        }

        // 按 index 映射回原始文档，按 relevance_score 降序排列
        List<Map.Entry<Integer, Double>> scoredIndices = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt();
            double score = result.path("relevance_score").asDouble();
            scoredIndices.add(Map.entry(index, score));
        }

        scoredIndices.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Document> rerankedDocs = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : scoredIndices) {
            int idx = entry.getKey();
            if (idx >= 0 && idx < candidates.size()) {
                Document doc = candidates.get(idx);
                // 将 rerank 分数添加到元数据
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                metadata.put("rerank_score", entry.getValue());
                rerankedDocs.add(new Document(doc.getId(), doc.getText(), metadata));
            }
        }

        log.debug("Rerank 完成: {} 个候选 → {} 个结果", candidates.size(), rerankedDocs.size());
        return rerankedDocs;
    }
}
