package com.yybbglory.ai.eatagent.rag.retrieval;

import com.yybbglory.ai.eatagent.util.TextUtils;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）算法实现
 * <p>
 * 将多个检索结果列表按排名融合为统一排序结果。
 * RRF 公式：score(d) = Σ 1/(k + rank)，其中 rank 为文档在各列表中的 0-based 位置。
 * <p>
 * 文档身份识别使用文本内容的 MD5 哈希值，与 C8 Python 实现行为一致。
 * <p>
 * 此类为纯工具类，不作为 Spring Bean。
 */
public final class RrfFusion {

    /** RRF 默认常数 k，用于平滑排名影响 */
    private static final int DEFAULT_K = 60;

    private RrfFusion() {
        // 工具类禁止实例化
    }

    /**
     * 融合多个检索结果列表
     *
     * @param resultLists 多个检索结果列表（每个列表已按相关性排序）
     * @param k           RRF 常数，控制排名衰减速度
     * @return 按 RRF 分数降序排列的融合结果，分数存储在 metadata 的 "rrf_score" 键中
     */
    public static List<Document> fuse(List<List<Document>> resultLists, int k) {
        // 边界情况：空输入
        if (resultLists == null || resultLists.isEmpty()) {
            return List.of();
        }

        // 过滤掉空列表
        List<List<Document>> validLists = resultLists.stream()
                .filter(list -> list != null && !list.isEmpty())
                .toList();

        if (validLists.isEmpty()) {
            return List.of();
        }

        // 如果只有一个有效列表，直接返回（附带 rrf_score）
        if (validLists.size() == 1) {
            return buildSingleListResult(validLists.getFirst(), k);
        }

        // 多列表融合：累加每个文档的 RRF 分数
        // key: 文档内容 MD5 哈希, value: 累计 RRF 分数
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        // key: 文档内容 MD5 哈希, value: 对应的原始文档（首次出现的版本）
        Map<String, Document> docMap = new LinkedHashMap<>();

        for (List<Document> resultList : validLists) {
            // 跟踪当前列表中已处理的文档哈希，避免同一列表内重复计分
            Set<String> seenInList = new HashSet<>();

            for (int rank = 0; rank < resultList.size(); rank++) {
                Document doc = resultList.get(rank);
                String contentHash = getDocumentHash(doc);

                // 同一列表内的重复文档只计第一次出现的排名
                if (!seenInList.add(contentHash)) {
                    continue;
                }

                // 计算当前排名的 RRF 贡献值
                double rrfContribution = 1.0 / (k + rank + 1);

                // 累加分数
                scoreMap.merge(contentHash, rrfContribution, Double::sum);

                // 保留首次出现的文档对象
                docMap.putIfAbsent(contentHash, doc);
            }
        }

        // 按 RRF 分数降序排序
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(scoreMap.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 构建结果文档列表
        List<Document> results = new ArrayList<>(sortedEntries.size());
        for (Map.Entry<String, Double> entry : sortedEntries) {
            Document original = docMap.get(entry.getKey());
            Map<String, Object> metadata = new HashMap<>(original.getMetadata());
            metadata.put("rrf_score", entry.getValue());

            Document fusedDoc = Document.builder()
                    .id(original.getId())
                    .text(original.getText())
                    .metadata(metadata)
                    .build();
            results.add(fusedDoc);
        }

        return results;
    }

    /**
     * 便捷方法：融合向量检索和 BM25 检索两个结果列表
     *
     * @param vectorResults 向量检索结果
     * @param bm25Results   BM25 检索结果
     * @param k             RRF 常数
     * @return 融合后的文档列表
     */
    public static List<Document> fuseTwo(List<Document> vectorResults, List<Document> bm25Results, int k) {
        List<List<Document>> lists = new ArrayList<>(2);
        if (vectorResults != null && !vectorResults.isEmpty()) {
            lists.add(vectorResults);
        }
        if (bm25Results != null && !bm25Results.isEmpty()) {
            lists.add(bm25Results);
        }
        return fuse(lists, k);
    }

    /**
     * 使用默认 k=60 融合两个结果列表
     *
     * @param vectorResults 向量检索结果
     * @param bm25Results   BM25 检索结果
     * @return 融合后的文档列表
     */
    public static List<Document> fuseTwo(List<Document> vectorResults, List<Document> bm25Results) {
        return fuseTwo(vectorResults, bm25Results, DEFAULT_K);
    }

    /**
     * 处理单列表场景：为每个文档分配 RRF 分数并返回
     *
     * @param docs 单个结果列表
     * @param k    RRF 常数
     * @return 带 rrf_score 的文档列表
     */
    private static List<Document> buildSingleListResult(List<Document> docs, int k) {
        List<Document> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int rank = 0; rank < docs.size(); rank++) {
            Document doc = docs.get(rank);
            String hash = getDocumentHash(doc);

            // 去重：同一列表内重复文档只保留首次出现
            if (!seen.add(hash)) {
                continue;
            }

            double rrfScore = 1.0 / (k + rank + 1);
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.put("rrf_score", rrfScore);

            Document scoredDoc = Document.builder()
                    .id(doc.getId())
                    .text(doc.getText())
                    .metadata(metadata)
                    .build();
            results.add(scoredDoc);
        }

        return results;
    }

    /**
     * 获取文档的内容哈希值作为唯一标识
     * 使用文档文本内容的 MD5 哈希，与 C8 Python 实现保持一致
     *
     * @param doc 文档对象
     * @return 文档内容的 MD5 哈希字符串
     */
    private static String getDocumentHash(Document doc) {
        String text = doc.getText();
        return TextUtils.md5Hash(text != null ? text : "");
    }
}
