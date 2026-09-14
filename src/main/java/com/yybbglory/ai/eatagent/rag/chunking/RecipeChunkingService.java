package com.yybbglory.ai.eatagent.rag.chunking;

import com.yybbglory.ai.eatagent.rag.document.ParentChildManager;
import com.yybbglory.ai.eatagent.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 食谱分块服务
 * <p>
 * 将 {@link com.yybbglory.ai.eatagent.rag.document.RecipeLoader} 加载的父文档
 * 按 Markdown 标题（#、##、###）拆分为子块，并为每个子块注入元数据，
 * 同时通过 {@link ParentChildManager} 注册父子关系。
 * <p>
 * 自定义 Markdown 标题分割逻辑：
 * <ul>
 *   <li>以 #、##、### 开头的行作为分割点</li>
 *   <li>标题行保留在对应子块内容的开头</li>
 *   <li>每个子块继承父文档的全部元数据，并追加 chunk_id、chunk_index、chunk_size、parent_id、doc_type 字段</li>
 * </ul>
 */
@Component
public class RecipeChunkingService {

    private static final Logger log = LoggerFactory.getLogger(RecipeChunkingService.class);

    /**
     * 匹配 Markdown 一级到三级标题的正则表达式
     * 匹配行首的 1~3 个 # 号后跟空格或行尾
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,3})\\s", Pattern.MULTILINE);

    private final ParentChildManager parentChildManager;

    public RecipeChunkingService(ParentChildManager parentChildManager) {
        this.parentChildManager = parentChildManager;
    }

    /**
     * 对父文档列表执行分块处理
     * <p>
     * 每个父文档按 Markdown 标题拆分为多个子块，
     * 同时在 {@link ParentChildManager} 中注册父子映射关系。
     *
     * @param parentDocs 父文档列表（由 RecipeLoader 生成）
     * @return 所有子块文档列表，可直接用于向量索引
     */
    public List<Document> chunkDocuments(List<Document> parentDocs) {
        List<Document> allChunks = new ArrayList<>();

        for (Document parentDoc : parentDocs) {
            // 注册父文档到缓存
            parentChildManager.registerParent(parentDoc);

            // 按 Markdown 标题分割内容
            List<String> chunkTexts = splitByMarkdownHeaders(parentDoc.getText());

            // 获取父文档 ID
            String parentId = (String) parentDoc.getMetadata().get("parent_id");

            // 为每个文本片段创建子块 Document
            for (int i = 0; i < chunkTexts.size(); i++) {
                String chunkText = chunkTexts.get(i);

                // 构建子块元数据：继承父文档元数据 + 子块专属字段
                Map<String, Object> chunkMetadata = new HashMap<>(parentDoc.getMetadata());
                // 稳定的内容哈希，作为 chunk 的唯一身份（持久化判定与跨重启映射均依赖它）
                String contentHash = TextUtils.md5Hash(chunkText);
                // chunk_id 由内容哈希派生，保证跨重启/增量重建时稳定，
                // 与持久化索引（向量库、BM25）中的 chunk_id 保持一致，父子映射才能正确回溯
                String chunkId = "chunk-" + contentHash;
                chunkMetadata.put("chunk_id", chunkId);
                chunkMetadata.put("content_hash", contentHash);
                chunkMetadata.put("chunk_index", i);
                chunkMetadata.put("chunk_size", chunkText.length());
                chunkMetadata.put("parent_id", parentId);
                chunkMetadata.put("doc_type", "child");

                Document chunkDoc = new Document(chunkId, chunkText, chunkMetadata);
                allChunks.add(chunkDoc);

                // 注册子块 → 父文档映射
                parentChildManager.registerChild(chunkId, parentId);
            }
        }

        log.info("分块完成: {} 个父文档 → {} 个子块", parentDocs.size(), allChunks.size());
        return allChunks;
    }

    /**
     * 按 Markdown 标题（#、##、###）分割文本
     * <p>
     * 分割规则：
     * <ol>
     *   <li>遍历每一行，检测是否为标题行（以 1~3 个 # 开头后跟空格）</li>
     *   <li>遇到标题行时，将之前累积的内容作为一个片段保存</li>
     *   <li>标题行本身归入新片段的开头</li>
     *   <li>文件开头到第一个标题之间的内容作为第零个片段</li>
     * </ol>
     *
     * @param content Markdown 格式的完整文本
     * @return 分割后的文本片段列表，每个片段非空
     */
    private List<String> splitByMarkdownHeaders(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String[] lines = content.split("\n", -1); // -1 保留尾部空行
        StringBuilder currentChunk = new StringBuilder();

        for (String line : lines) {
            // 判断当前行是否为 Markdown 标题行
            if (isHeaderLine(line)) {
                // 如果已有累积内容，保存为一个片段
                if (!currentChunk.isEmpty()) {
                    String text = currentChunk.toString().strip();
                    if (!text.isEmpty()) {
                        chunks.add(text);
                    }
                }
                // 标题行作为新片段的起始
                currentChunk = new StringBuilder(line).append("\n");
            } else {
                currentChunk.append(line).append("\n");
            }
        }

        // 处理最后一个片段
        if (!currentChunk.isEmpty()) {
            String text = currentChunk.toString().strip();
            if (!text.isEmpty()) {
                chunks.add(text);
            }
        }

        // 兜底：如果未检测到任何标题，整篇内容作为一个片段
        if (chunks.isEmpty() && !content.isBlank()) {
            chunks.add(content.strip());
        }

        return chunks;
    }

    /**
     * 判断一行文本是否为 Markdown 标题行（# ~ ###）
     *
     * @param line 单行文本
     * @return true 如果是标题行
     */
    private boolean isHeaderLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }

        int hashCount = 0;
        for (int i = 0; i < line.length() && i < 4; i++) {
            if (line.charAt(i) == '#') {
                hashCount++;
            } else {
                break;
            }
        }

        // 1~3 个 # 且后面紧跟空格或已到行尾
        return hashCount >= 1 && hashCount <= 3
                && (hashCount == line.length() || line.charAt(hashCount) == ' ');
    }
}
