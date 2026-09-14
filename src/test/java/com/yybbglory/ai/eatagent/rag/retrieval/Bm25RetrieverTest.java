package com.yybbglory.ai.eatagent.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25 检索器单元测试
 */
class Bm25RetrieverTest {

    /**
     * 测试基本检索功能：查询应返回相关文档
     */
    @Test
    void testBasicSearchReturnsResults() {
        List<Document> docs = List.of(
                new Document("红烧肉是一道经典的中国家常菜"),
                new Document("番茄炒蛋是简单又美味的家常菜"),
                new Document("今天天气很好适合出去散步")
        );

        Bm25Retriever retriever = new Bm25Retriever(docs);
        List<Document> results = retriever.search("红烧肉", 10);

        assertFalse(results.isEmpty(), "查询'红烧肉'应返回至少一个结果");
        // 第一个结果应该是包含"红烧肉"的文档
        assertTrue(results.getFirst().getText().contains("红烧肉"),
                "最相关的文档应排在首位");
    }

    /**
     * 测试 topK 限制：返回结果数不超过 topK
     */
    @Test
    void testTopKLimiting() {
        List<Document> docs = List.of(
                new Document("红烧肉做法"),
                new Document("红烧排骨做法"),
                new Document("红烧鱼做法"),
                new Document("红烧茄子做法"),
                new Document("清蒸鲈鱼做法")
        );

        Bm25Retriever retriever = new Bm25Retriever(docs);
        List<Document> results = retriever.search("红烧", 2);

        assertEquals(2, results.size(), "topK=2 时应只返回 2 个结果");
    }

    /**
     * 测试空查询返回空结果
     */
    @Test
    void testEmptyQueryReturnsEmpty() {
        List<Document> docs = List.of(new Document("红烧肉做法"));
        Bm25Retriever retriever = new Bm25Retriever(docs);

        assertTrue(retriever.search("", 10).isEmpty(), "空字符串查询应返回空列表");
        assertTrue(retriever.search(null, 10).isEmpty(), "null 查询应返回空列表");
        assertTrue(retriever.search("   ", 10).isEmpty(), "纯空白查询应返回空列表");
    }

    /**
     * 测试空语料返回空结果
     */
    @Test
    void testEmptyCorpusReturnsEmpty() {
        Bm25Retriever retriever = new Bm25Retriever(List.of());
        List<Document> results = retriever.search("红烧肉", 10);

        assertTrue(results.isEmpty(), "空语料时任何查询都应返回空列表");
    }

    /**
     * 测试 null 语料不会抛异常
     */
    @Test
    void testNullCorpusHandledGracefully() {
        Bm25Retriever retriever = new Bm25Retriever(null);
        List<Document> results = retriever.search("红烧肉", 10);

        assertTrue(results.isEmpty(), "null 语料时应返回空列表");
    }

    /**
     * 测试评分排序：更相关的文档排名更高
     */
    @Test
    void testScoringOrdering() {
        List<Document> docs = List.of(
                new Document("今天天气不错"),                           // 不相关
                new Document("红烧肉红烧肉红烧肉"),                     // 高度相关（多次出现）
                new Document("这道红烧肉非常好吃"),                      // 中度相关
                new Document("我喜欢吃苹果")                            // 不相关
        );

        Bm25Retriever retriever = new Bm25Retriever(docs);
        List<Document> results = retriever.search("红烧肉", 10);

        assertTrue(results.size() >= 2, "应至少返回 2 个包含'红烧肉'的文档");

        // 验证分数递减
        for (int i = 1; i < results.size(); i++) {
            double prevScore = (double) results.get(i - 1).getMetadata().get("bm25_score");
            double currScore = (double) results.get(i).getMetadata().get("bm25_score");
            assertTrue(prevScore >= currScore,
                    "BM25 分数应按降序排列，但第 " + (i - 1) + " 项分数 " + prevScore
                            + " < 第 " + i + " 项分数 " + currScore);
        }

        // 最高分文档应是包含最多"红烧肉"的那个
        assertEquals("红烧肉红烧肉红烧肉", results.getFirst().getText(),
                "词频最高的文档应排名第一");
    }

    /**
     * 测试 bm25_score 正确写入 metadata
     */
    @Test
    void testBm25ScoreInMetadata() {
        List<Document> docs = List.of(new Document("红烧肉做法大全"));
        Bm25Retriever retriever = new Bm25Retriever(docs);

        List<Document> results = retriever.search("红烧肉", 10);

        assertFalse(results.isEmpty());
        Object score = results.getFirst().getMetadata().get("bm25_score");
        assertNotNull(score, "metadata 中应包含 bm25_score");
        assertInstanceOf(Double.class, score, "bm25_score 应为 Double 类型");
        assertTrue((double) score > 0, "bm25_score 应为正值");
    }

    /**
     * 测试无匹配查询返回空结果
     */
    @Test
    void testNoMatchReturnsEmpty() {
        List<Document> docs = List.of(
                new Document("红烧肉做法"),
                new Document("番茄炒蛋")
        );

        Bm25Retriever retriever = new Bm25Retriever(docs);
        // 查询一个完全不在语料中的词
        List<Document> results = retriever.search("量子力学", 10);

        assertTrue(results.isEmpty(), "查询完全不相关的词应返回空列表");
    }

    /**
     * 测试中文分词：中文字符应被逐字拆分
     */
    @Test
    void testChineseTokenization() {
        List<String> tokens = Bm25Retriever.tokenize("红烧肉,hello世界");

        // "红"、"烧"、"肉" 应各自为独立 token
        assertTrue(tokens.contains("红"), "应包含单字 '红'");
        assertTrue(tokens.contains("烧"), "应包含单字 '烧'");
        assertTrue(tokens.contains("肉"), "应包含单字 '肉'");
        // "hello" 应作为一个完整 token
        assertTrue(tokens.contains("hello"), "英文单词应保持完整");
        assertTrue(tokens.contains("世"), "应包含单字 '世'");
        assertTrue(tokens.contains("界"), "应包含单字 '界'");
    }
}
