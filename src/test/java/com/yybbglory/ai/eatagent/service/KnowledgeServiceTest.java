package com.yybbglory.ai.eatagent.service;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.rag.chunking.RecipeChunkingService;
import com.yybbglory.ai.eatagent.rag.document.ParentChildManager;
import com.yybbglory.ai.eatagent.rag.document.RecipeLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 知识库服务单元测试
 * <p>
 * 覆盖菜品详情 markdown 中相对路径图片引用的重写逻辑，
 * 以及图片资源解析（含路径穿越防护）。
 */
class KnowledgeServiceTest {

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(
                mock(RecipeLoader.class),
                mock(RecipeChunkingService.class),
                mock(ParentChildManager.class),
                mock(VectorStore.class),
                mock(RagProperties.class),
                mock(ResourceLoader.class));
    }

    @Test
    void rewriteRelativeImagePathsToAbsoluteApiUrl() {
        String content = "前置文本\n"
                + "![干木耳](1.jpg)\n"
                + "![木耳](2.jpg \"泡发后\")\n"
                + "![绝对图](https://example.com/a.png)\n"
                + "![根路径](/static/a.png)\n"
                + "![数据图](data:image/png;base64,xxx)";

        String rewritten = service.rewriteImagePaths(content, "parent-abc");

        // 相对路径被重写为绝对 API 地址
        assertThat(rewritten).contains("![干木耳](/api/knowledge/images/parent-abc/1.jpg)");
        // 带 title 的引用保留 title
        assertThat(rewritten).contains("![木耳](/api/knowledge/images/parent-abc/2.jpg \"泡发后\")");
        // 绝对地址保持不变
        assertThat(rewritten).contains("![绝对图](https://example.com/a.png)");
        assertThat(rewritten).contains("![根路径](/static/a.png)");
        assertThat(rewritten).contains("![数据图](data:image/png;base64,xxx)");
        // 非图片文本保持不变
        assertThat(rewritten).startsWith("前置文本");
    }

    @Test
    void rewriteImagePathsHandlesNullOrBlankContent() {
        assertThat(service.rewriteImagePaths(null, "p")).isNull();
        assertThat(service.rewriteImagePaths("   ", "p")).isEqualTo("   ");
        assertThat(service.rewriteImagePaths("![a](1.jpg)", "")).isEqualTo("![a](1.jpg)");
        assertThat(service.rewriteImagePaths("![a](1.jpg)", null)).isEqualTo("![a](1.jpg)");
    }

    @Test
    void resolveImageServesSiblingImageAndRejectsTraversal() throws IOException {
        // 构造临时食谱目录：md 文件 + 同目录图片
        Path dir = Files.createTempDirectory("eatagent-test");
        Path mdFile = dir.resolve("凉拌木耳.md");
        Path imageFile = dir.resolve("1.jpg");
        Files.writeString(mdFile, "# 测试\n![图](1.jpg)");
        Files.write(imageFile, new byte[]{1, 2, 3});

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("parent_id", "parent-test");
        metadata.put("source", mdFile.toString());
        Document parentDoc = new Document("parent-test", "内容", metadata);
        ReflectionTestUtils.setField(service, "parentDocuments", List.of(parentDoc));

        // 正常解析同目录图片
        Resource resource = service.resolveImage("parent-test", "1.jpg");
        assertThat(resource).isNotNull();
        assertThat(resource.exists()).isTrue();

        // 不存在的图片返回 null
        assertThat(service.resolveImage("parent-test", "not-exist.jpg")).isNull();

        // 路径穿越被拒绝
        assertThat(service.resolveImage("parent-test", "../../etc/passwd")).isNull();

        // 未知 parentId 返回 null
        assertThat(service.resolveImage("unknown", "1.jpg")).isNull();
    }
}
