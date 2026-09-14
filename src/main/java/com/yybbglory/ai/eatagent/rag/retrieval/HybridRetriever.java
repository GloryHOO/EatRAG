package com.yybbglory.ai.eatagent.rag.retrieval;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索编排器
 * 完整 pipeline: 向量检索 + BM25 → RRF 融合 → 元数据过滤 → Rerank 精排
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final VectorStore vectorStore;
    private final KnowledgeService knowledgeService;
    private final RerankService rerankService;
    private final RagProperties properties;

    public HybridRetriever(VectorStore vectorStore,
                           KnowledgeService knowledgeService,
                           RerankService rerankService,
                           RagProperties properties) {
        this.vectorStore = vectorStore;
        this.knowledgeService = knowledgeService;
        this.rerankService = rerankService;
        this.properties = properties;
    }

    /**
     * 执行混合检索
     *
     * @param query   用户查询（已经过重写）
     * @param filters 元数据过滤条件（可为空）
     * @return 检索到的文档块列表
     */
    public List<Document> retrieve(String query, Map<String, String> filters) {
        int topK = properties.rag() != null ? properties.rag().topK() : 3;
        int multiplier = properties.rag() != null ? properties.rag().candidateMultiplier() : 3;
        int rrfK = properties.rag() != null ? properties.rag().rrfK() : 60;
        int candidateCount = topK * multiplier;

        // 1. 向量检索
        List<Document> vectorResults = vectorSearch(query, candidateCount);
        log.debug("向量检索返回 {} 个结果", vectorResults.size());

        // 2. BM25 检索
        Bm25Retriever bm25 = knowledgeService.getBm25Retriever();
        List<Document> bm25Results = (bm25 != null) ? bm25.search(query, candidateCount) : List.of();
        log.debug("BM25 检索返回 {} 个结果", bm25Results.size());

        // 3. RRF 融合
        List<Document> fusedResults = RrfFusion.fuseTwo(vectorResults, bm25Results, rrfK);
        log.debug("RRF 融合后 {} 个结果", fusedResults.size());

        // 4. 元数据后过滤
        if (filters != null && !filters.isEmpty()) {
            fusedResults = applyMetadataFilter(fusedResults, filters, candidateCount);
            log.debug("元数据过滤后 {} 个结果", fusedResults.size());
        }

        // 5. Rerank 精排（取 top-N 候选给 rerank）
        List<Document> rerankCandidates = fusedResults.subList(0, Math.min(fusedResults.size(), candidateCount));
        List<Document> rerankedResults = rerankService.rerank(query, rerankCandidates);

        // 6. 取 top-K
        List<Document> finalResults = rerankedResults.subList(0, Math.min(rerankedResults.size(), topK));
        log.info("最终检索结果: {} 个文档块", finalResults.size());
        return finalResults;
    }

    /**
     * 向量检索
     */
    private List<Document> vectorSearch(String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 应用元数据过滤
     */
    private List<Document> applyMetadataFilter(List<Document> docs, Map<String, String> filters, int maxResults) {
        List<Document> filtered = new ArrayList<>();
        for (Document doc : docs) {
            boolean match = true;
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                Object value = doc.getMetadata().get(filter.getKey());
                if (value == null || !value.toString().equals(filter.getValue())) {
                    match = false;
                    break;
                }
            }
            if (match) {
                filtered.add(doc);
                if (filtered.size() >= maxResults) {
                    break;
                }
            }
        }
        return filtered;
    }
}
