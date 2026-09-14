package com.yybbglory.ai.eatagent.rag.retrieval;

import org.springframework.ai.document.Document;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 手写轻量级 BM25 检索器
 * <p>
 * 零外部依赖（不引入 Lucene），适用于中小规模中文文档语料（如 323 篇食谱）。
 * 中文分词采用字符级 + 标点分割策略，在无专业中文分词器的情况下可接受。
 * <p>
 * BM25 公式：
 * score(D,Q) = Σ IDF(qi) * (tf(qi,D) * (k1+1)) / (tf(qi,D) + k1*(1 - b + b*|D|/avgdl))
 * IDF(qi) = ln((N - n(qi) + 0.5) / (n(qi) + 0.5) + 1)
 */
public class Bm25Retriever {

    /** BM25 参数 k1，控制词频饱和度 */
    private static final double K1 = 1.5;

    /** BM25 参数 b，控制文档长度归一化程度 */
    private static final double B = 0.75;

    /** 用于分割中英文标点和空白字符的正则 */
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile(
            "[\\s\\p{Punct}\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A\u201C\u201D\u2018\u2019\uFF08\uFF09\u300A\u300B\u3010\u3011\u2026\u2014\u00B7]+"
    );

    /** 文档语料 */
    private final List<Document> documents;

    /** 每个文档的分词结果（token 列表） */
    private final List<List<String>> docTokens;

    /** 每个文档的词项频率映射 */
    private final List<Map<String, Integer>> docTermFreqs;

    /** 全局词项的文档频率（包含该词项的文档数） */
    private final Map<String, Integer> docFreqs;

    /** 所有词项的 IDF 值 */
    private final Map<String, Double> idfMap;

    /** 平均文档长度（以 token 数计） */
    private final double avgDocLength;

    /** 文档总数 */
    private final int numDocs;

    /**
     * 构造函数：传入文档语料并构建 BM25 索引
     *
     * @param documents Spring AI Document 列表
     */
    public Bm25Retriever(List<Document> documents) {
        this.documents = documents != null ? List.copyOf(documents) : List.of();
        this.numDocs = this.documents.size();

        if (this.numDocs == 0) {
            // 空语料快速返回
            this.docTokens = List.of();
            this.docTermFreqs = List.of();
            this.docFreqs = Map.of();
            this.idfMap = Map.of();
            this.avgDocLength = 0.0;
            return;
        }

        // 对每个文档进行分词并统计词频
        this.docTokens = new ArrayList<>(this.numDocs);
        this.docTermFreqs = new ArrayList<>(this.numDocs);
        this.docFreqs = new HashMap<>();

        long totalTokens = 0;
        for (Document doc : this.documents) {
            List<String> tokens = tokenize(doc.getText());
            this.docTokens.add(tokens);
            totalTokens += tokens.size();

            // 统计当前文档的词频
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }
            this.docTermFreqs.add(termFreq);

            // 更新文档频率（每个词项在当前文档中出现即计 1）
            for (String term : termFreq.keySet()) {
                this.docFreqs.merge(term, 1, Integer::sum);
            }
        }

        this.avgDocLength = (double) totalTokens / this.numDocs;

        // 预计算所有词项的 IDF
        this.idfMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : this.docFreqs.entrySet()) {
            this.idfMap.put(entry.getKey(), computeIdf(entry.getValue()));
        }
    }

    /**
     * 执行 BM25 检索
     *
     * @param query 查询文本
     * @param topK  返回的最大文档数
     * @return 按 BM25 分数降序排列的文档列表，分数存储在 metadata 的 "bm25_score" 键中
     */
    public List<Document> search(String query, int topK) {
        // 边界情况：空查询或空语料
        if (query == null || query.isBlank() || this.numDocs == 0) {
            return List.of();
        }

        // 对查询进行分词
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        // 计算每个文档的 BM25 分数
        List<Map.Entry<Integer, Double>> scoredDocs = new ArrayList<>();
        for (int i = 0; i < this.numDocs; i++) {
            double score = computeBm25Score(i, queryTokens);
            if (score > 0) {
                scoredDocs.add(Map.entry(i, score));
            }
        }

        // 按分数降序排序
        scoredDocs.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 截取 topK 并构建结果文档（附带 bm25_score）
        int resultSize = Math.min(topK, scoredDocs.size());
        List<Document> results = new ArrayList<>(resultSize);
        for (int i = 0; i < resultSize; i++) {
            Map.Entry<Integer, Double> entry = scoredDocs.get(i);
            Document original = this.documents.get(entry.getKey());

            // 创建新文档副本，将 bm25_score 写入 metadata
            Map<String, Object> metadata = new HashMap<>(original.getMetadata());
            metadata.put("bm25_score", entry.getValue());
            Document scoredDoc = Document.builder()
                    .id(original.getId())
                    .text(original.getText())
                    .metadata(metadata)
                    .build();
            results.add(scoredDoc);
        }

        return results;
    }

    /**
     * 计算单个文档对给定查询词项列表的 BM25 分数
     *
     * @param docIndex   文档索引
     * @param queryTerms 查询词项列表
     * @return BM25 分数
     */
    private double computeBm25Score(int docIndex, List<String> queryTerms) {
        Map<String, Integer> termFreq = this.docTermFreqs.get(docIndex);
        int docLength = this.docTokens.get(docIndex).size();
        double score = 0.0;

        for (String term : queryTerms) {
            Double idf = this.idfMap.get(term);
            if (idf == null) {
                // 查询词项不在语料中，跳过
                continue;
            }
            int tf = termFreq.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }

            // BM25 核心公式
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * docLength / this.avgDocLength);
            score += idf * numerator / denominator;
        }

        return score;
    }

    /**
     * 计算词项的 IDF（逆文档频率）
     * IDF(qi) = ln((N - n(qi) + 0.5) / (n(qi) + 0.5) + 1)
     *
     * @param docFreq 包含该词项的文档数
     * @return IDF 值
     */
    private double computeIdf(int docFreq) {
        return Math.log((this.numDocs - docFreq + 0.5) / (docFreq + 0.5) + 1.0);
    }

    /**
     * 中文文本分词
     * <p>
     * 策略：先按标点和空白分割，再对每个片段中的中文字符逐字拆分，
     * 非中文部分（如英文单词、数字）保持为一个 token。
     * 这对于 323 篇食谱级别的语料是可接受的近似方案。
     *
     * @param text 输入文本
     * @return 词项列表（已过滤空串）
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        // 第一步：按标点和空白分割
        String[] segments = TOKEN_SPLIT_PATTERN.split(text);

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            // 第二步：对每个片段进行中文字符拆分
            splitChineseChars(segment, tokens);
        }

        return tokens;
    }

    /**
     * 将字符串中的中文字符逐字拆分，非中文字符连续部分保留为一个 token
     *
     * @param segment 输入片段
     * @param tokens  输出词项列表
     */
    private static void splitChineseChars(String segment, List<String> tokens) {
        StringBuilder nonChineseBuf = new StringBuilder();

        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (isChinese(c)) {
                // 遇到中文字符前，先输出累积的非中文 token
                if (!nonChineseBuf.isEmpty()) {
                    tokens.add(nonChineseBuf.toString().toLowerCase());
                    nonChineseBuf.setLength(0);
                }
                // 中文字符单独作为一个 token
                tokens.add(String.valueOf(c));
            } else {
                nonChineseBuf.append(c);
            }
        }

        // 处理末尾剩余的非中文部分
        if (!nonChineseBuf.isEmpty()) {
            tokens.add(nonChineseBuf.toString().toLowerCase());
        }
    }

    /**
     * 判断字符是否为 CJK 统一汉字
     *
     * @param c 待判断字符
     * @return 是否为中文字符
     */
    private static boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
