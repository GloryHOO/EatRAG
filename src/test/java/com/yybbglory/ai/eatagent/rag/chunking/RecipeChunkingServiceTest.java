package com.yybbglory.ai.eatagent.rag.chunking;

import com.yybbglory.ai.eatagent.rag.document.ParentChildManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块服务单元测试
 * <p>
 * 核心验证点：chunk_id 由内容哈希派生，跨多次调用保持稳定，
 * 从而保证持久化索引（向量库/BM25）中的 chunk_id 与 ParentChildManager 注册的映射一致，
 * 检索命中后能够正确回溯到父文档。
 */
class RecipeChunkingServiceTest {

    private static final String RECIPE_TEXT = """
            # 清炒时蔬
            这是一道简单的素菜。
            ## 食材
            白菜、蒜、盐、油。
            ## 步骤
            1. 洗净白菜。
            2. 热锅下油爆香蒜。
            3. 下白菜大火快炒，加盐调味。
            """;

    private Document createParentDoc(String parentId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("parent_id", parentId);
        metadata.put("dish_name", "清炒时蔬");
        metadata.put("category", "素菜");
        metadata.put("difficulty", "简单");
        return new Document(parentId, RECIPE_TEXT, metadata);
    }

    @Test
    void chunkIdStableAcrossMultipleChunkingRuns() {
        // 模拟两次启动：同一份食谱内容重新分块
        RecipeChunkingService firstRun = new RecipeChunkingService(new ParentChildManager());
        RecipeChunkingService secondRun = new RecipeChunkingService(new ParentChildManager());

        List<Document> firstChunks = firstRun.chunkDocuments(List.of(createParentDoc("parent-1")));
        List<Document> secondChunks = secondRun.chunkDocuments(List.of(createParentDoc("parent-1")));

        assertThat(firstChunks).hasSameSizeAs(secondChunks);

        // 相同内容的 chunk 必须生成相同的 chunk_id（跨重启稳定）
        for (int i = 0; i < firstChunks.size(); i++) {
            assertThat(secondChunks.get(i).getMetadata().get("chunk_id"))
                    .as("第 %d 个 chunk 的 chunk_id 应跨调用稳定", i)
                    .isEqualTo(firstChunks.get(i).getMetadata().get("chunk_id"));
        }

        // chunk_id 必须符合规范格式（由内容哈希派生），且与 content_hash 一致
        for (Document chunk : firstChunks) {
            String chunkId = (String) chunk.getMetadata().get("chunk_id");
            String contentHash = (String) chunk.getMetadata().get("content_hash");
            assertThat(chunkId).isEqualTo("chunk-" + contentHash);
        }
    }

    @Test
    void parentChildMappingWorksWithStableChunkIds() {
        // 用第一次分块的 ParentChildManager 注册映射（模拟持久化索引中的 chunk_id）
        ParentChildManager firstManager = new ParentChildManager();
        RecipeChunkingService firstRun = new RecipeChunkingService(firstManager);
        List<Document> chunks = firstRun.chunkDocuments(List.of(createParentDoc("parent-1")));

        // 模拟重启：第二次分块生成相同的 chunk_id，新 ParentChildManager 注册的映射必须能回溯
        ParentChildManager secondManager = new ParentChildManager();
        RecipeChunkingService secondRun = new RecipeChunkingService(secondManager);
        secondRun.chunkDocuments(List.of(createParentDoc("parent-1")));

        // 用第一次（旧）chunk 命中第二次（新）映射，必须能回溯到父文档
        List<Document> parents = secondManager.getParentDocuments(chunks);
        assertThat(parents).hasSize(1);
        assertThat(parents.getFirst().getMetadata().get("parent_id")).isEqualTo("parent-1");
    }
}
