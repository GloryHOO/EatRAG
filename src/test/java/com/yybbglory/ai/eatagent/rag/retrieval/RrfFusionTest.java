package com.yybbglory.ai.eatagent.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RRF（倒数排名融合）算法单元测试
 */
class RrfFusionTest {

    /**
     * 测试两列表融合及已知分数验证
     */
    @Test
    void testTwoListFusionWithKnownScores() {
        // 向量检索结果：docA 排第1，docB 排第2
        List<Document> vectorResults = List.of(
                new Document("文档A内容"),
                new Document("文档B内容")
        );

        // BM25 检索结果：docB 排第1，docC 排第2
        List<Document> bm25Results = List.of(
                new Document("文档B内容"),
                new Document("文档C内容")
        );

        int k = 60;
        List<Document> fused = RrfFusion.fuseTwo(vectorResults, bm25Results, k);

        // docB 出现在两个列表中，RRF 分数应最高
        assertEquals("文档B内容", fused.getFirst().getText(),
                "同时出现在两个列表中的文档应排名第一");

        // 验证 docB 的 RRF 分数 = 1/(60+1+1) + 1/(60+0+1) = 1/62 + 1/61
        // docB 在向量列表中 rank=1，在 BM25 列表中 rank=0
        double expectedDocBScore = 1.0 / (k + 1 + 1) + 1.0 / (k + 0 + 1);
        double actualDocBScore = (double) fused.getFirst().getMetadata().get("rrf_score");
        assertEquals(expectedDocBScore, actualDocBScore, 1e-10,
                "docB 的 RRF 分数应为 1/62 + 1/61");
    }

    /**
     * 测试单列表直通
     */
    @Test
    void testSingleListPassthrough() {
        List<Document> singleList = List.of(
                new Document("文档一"),
                new Document("文档二"),
                new Document("文档三")
        );

        List<Document> fused = RrfFusion.fuse(List.of(singleList), 60);

        assertEquals(3, fused.size(), "单列表应返回所有文档");
        assertEquals("文档一", fused.get(0).getText(), "顺序应保持");
        assertEquals("文档二", fused.get(1).getText());
        assertEquals("文档三", fused.get(2).getText());

        // 验证第一个文档的 RRF 分数
        double expectedScore = 1.0 / (60 + 1);
        double actualScore = (double) fused.get(0).getMetadata().get("rrf_score");
        assertEquals(expectedScore, actualScore, 1e-10,
                "单列表首个文档的 RRF 分数应为 1/(k+1)");
    }

    /**
     * 测试空列表处理
     */
    @Test
    void testEmptyLists() {
        // null 输入
        assertTrue(RrfFusion.fuse(null, 60).isEmpty(), "null 输入应返回空列表");

        // 空列表
        assertTrue(RrfFusion.fuse(List.of(), 60).isEmpty(), "空列表输入应返回空列表");

        // 包含空子列表
        assertTrue(RrfFusion.fuse(List.of(List.of(), List.of()), 60).isEmpty(),
                "所有子列表为空时应返回空列表");

        // fuseTwo 两个空列表
        assertTrue(RrfFusion.fuseTwo(List.of(), List.of(), 60).isEmpty(),
                "fuseTwo 两个空列表应返回空列表");

        // fuseTwo null 参数
        assertTrue(RrfFusion.fuseTwo(null, null, 60).isEmpty(),
                "fuseTwo null 参数应返回空列表");
    }

    /**
     * 测试重复文档合并（相同内容在两个列表中都出现）
     */
    @Test
    void testDuplicateDocumentMerging() {
        String sameContent = "完全相同的文档内容";

        List<Document> list1 = List.of(new Document(sameContent));
        List<Document> list2 = List.of(new Document(sameContent));

        List<Document> fused = RrfFusion.fuseTwo(list1, list2, 60);

        // 相同内容的文档应被合并为一个
        assertEquals(1, fused.size(), "相同内容的文档应合并为一条");

        // 分数应为两个列表 rank 0 的贡献之和
        double expectedScore = 1.0 / 61 + 1.0 / 61;
        double actualScore = (double) fused.getFirst().getMetadata().get("rrf_score");
        assertEquals(expectedScore, actualScore, 1e-10,
                "合并后的分数应为两次 rank 0 贡献之和");
    }

    /**
     * 测试同一列表内的重复文档只计第一次
     */
    @Test
    void testDuplicateWithinSameList() {
        String sameContent = "重复内容";

        List<Document> listWithDupes = List.of(
                new Document(sameContent),
                new Document("其他内容"),
                new Document(sameContent)  // 重复
        );

        List<Document> fused = RrfFusion.fuse(List.of(listWithDupes), 60);

        // 应只有 2 个唯一文档
        assertEquals(2, fused.size(), "同一列表内重复文档应去重");

        // 重复文档的分数应只计 rank 0 的贡献
        double expectedScore = 1.0 / 61;
        double actualScore = (double) fused.get(0).getMetadata().get("rrf_score");
        assertEquals(expectedScore, actualScore, 1e-10,
                "同一列表内重复文档只计首次出现的排名");
    }

    /**
     * 测试 RRF 公式验证：rank 0 时 score = 1/(60+1) ≈ 0.01639
     */
    @Test
    void testRrfFormulaRankZero() {
        List<Document> docs = List.of(new Document("测试文档"));
        int k = 60;

        List<Document> fused = RrfFusion.fuse(List.of(docs), k);

        double rrfScore = (double) fused.getFirst().getMetadata().get("rrf_score");
        double expected = 1.0 / (k + 1);

        assertEquals(expected, rrfScore, 1e-10,
                "rank 0 的 RRF 分数应为 1/(k+1) ≈ 0.01639");
        assertEquals(1.0 / 61, rrfScore, 1e-5,
                "具体数值应约为 0.01639");
    }

    /**
     * 测试分数累加正确性：多列表多排名
     */
    @Test
    void testScoreAccumulationCorrectness() {
        // 构造三个列表，让同一个文档出现在不同排名位置
        String targetContent = "目标文档";
        int k = 60;

        List<Document> list1 = List.of(
                new Document(targetContent),       // rank 0
                new Document("其他1")
        );
        List<Document> list2 = List.of(
                new Document("其他2"),
                new Document(targetContent)         // rank 1
        );
        List<Document> list3 = List.of(
                new Document("其他3"),
                new Document("其他4"),
                new Document(targetContent)         // rank 2
        );

        List<Document> fused = RrfFusion.fuse(List.of(list1, list2, list3), k);

        // 找到目标文档
        Document targetDoc = fused.stream()
                .filter(d -> d.getText().equals(targetContent))
                .findFirst()
                .orElseThrow();

        // 期望分数 = 1/(60+1) + 1/(60+2) + 1/(60+3)
        double expected = 1.0 / 61 + 1.0 / 62 + 1.0 / 63;
        double actual = (double) targetDoc.getMetadata().get("rrf_score");

        assertEquals(expected, actual, 1e-10,
                "RRF 分数应正确累加三个不同排名的贡献值");
    }

    /**
     * 测试 rrf_score 存在于 metadata 中
     */
    @Test
    void testRrfScoreInMetadata() {
        List<Document> docs = List.of(new Document("测试"));
        List<Document> fused = RrfFusion.fuse(List.of(docs), 60);

        assertNotNull(fused.getFirst().getMetadata().get("rrf_score"),
                "metadata 中应包含 rrf_score");
        assertInstanceOf(Double.class, fused.getFirst().getMetadata().get("rrf_score"),
                "rrf_score 应为 Double 类型");
    }

    /**
     * 测试默认 k 值的 fuseTwo 方法
     */
    @Test
    void testFuseTwoDefaultK() {
        List<Document> list1 = List.of(new Document("文档A"));
        List<Document> list2 = List.of(new Document("文档B"));

        // 使用无 k 参数的重载方法
        List<Document> fused = RrfFusion.fuseTwo(list1, list2);

        assertFalse(fused.isEmpty());
        // 默认 k=60，rank 0 的分数应为 1/61
        double score = (double) fused.getFirst().getMetadata().get("rrf_score");
        assertEquals(1.0 / 61, score, 1e-10,
                "默认 k=60 时 rank 0 分数应为 1/61");
    }
}
