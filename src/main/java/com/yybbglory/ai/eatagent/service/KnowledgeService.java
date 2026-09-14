package com.yybbglory.ai.eatagent.service;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.model.dto.DocumentDetail;
import com.yybbglory.ai.eatagent.model.dto.DocumentSummary;
import com.yybbglory.ai.eatagent.model.dto.KnowledgeStatsResponse;
import com.yybbglory.ai.eatagent.model.dto.PagedDocuments;
import com.yybbglory.ai.eatagent.exception.BusinessException;
import com.yybbglory.ai.eatagent.rag.chunking.RecipeChunkingService;
import com.yybbglory.ai.eatagent.rag.document.ParentChildManager;
import com.yybbglory.ai.eatagent.rag.document.RecipeLoader;
import com.yybbglory.ai.eatagent.rag.retrieval.Bm25Retriever;
import com.yybbglory.ai.eatagent.util.DocumentStoreUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库管理服务
 * 负责索引构建（支持增量）、统计信息查询、全量重建、异步持久化
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 异步持久化线程池（虚拟线程） */
    private static final ExecutorService PERSIST_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final RecipeLoader recipeLoader;
    private final RecipeChunkingService chunkingService;
    private final ParentChildManager parentChildManager;
    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final ResourceLoader resourceLoader;

    private volatile Bm25Retriever bm25Retriever;
    /** 当前内存中已索引的全部 chunk（用于 BM25 全量重建与持久化） */
    private volatile List<Document> indexedChunks = List.of();
    /** 当前已加载的父文档（完整食谱），供文档列表/详情查询使用 */
    private volatile List<Document> parentDocuments = List.of();
    private final AtomicBoolean indexReady = new AtomicBoolean(false);
    private volatile int totalDocuments = 0;
    private volatile int totalChunks = 0;
    private volatile Map<String, Integer> categoryStats = Map.of();
    private volatile Map<String, Integer> difficultyStats = Map.of();

    /** Markdown 图片语法正则：![alt](src) 或 ![alt](src "title") */
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)((?:\\s+\"[^\"]*\")?)\\)");

    public KnowledgeService(RecipeLoader recipeLoader,
                            RecipeChunkingService chunkingService,
                            ParentChildManager parentChildManager,
                            VectorStore vectorStore,
                            RagProperties properties,
                            ResourceLoader resourceLoader) {
        this.recipeLoader = recipeLoader;
        this.chunkingService = chunkingService;
        this.parentChildManager = parentChildManager;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 应用启动时自动构建索引
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            buildIndex();
        } catch (Exception e) {
            log.error("启动时索引构建失败: {}", e.getMessage(), e);
            log.warn("应用将继续运行，但问答功能不可用。请通过 POST /api/knowledge/rebuild 手动重建索引。");
        }
    }

    /**
     * 构建索引（支持增量）
     * <p>
     * 流程：
     * 1. 加载食谱文档并分块
     * 2. 加载已保存的 BM25 文件，得到"已处理 chunk"的 content_hash 集合（权威记录）
     * 3. 向量层：若存在已保存的向量文件则直接加载；对未处理的 chunk 执行 embedding 并加入
     * 4. BM25 层：合并已存 chunk 与新增 chunk，全量重建 BM25 索引
     * 5. 异步将向量索引和 BM25 索引写入本地文件
     *
     * @return 构建结果统计
     */
    public synchronized Map<String, Object> buildIndex() {
        long startTime = System.currentTimeMillis();
        log.info("开始构建知识库索引...");

        // 1. 加载文档
        List<Document> parentDocs = recipeLoader.loadRecipes();
        totalDocuments = parentDocs.size();
        this.parentDocuments = parentDocs;
        log.info("加载了 {} 篇食谱文档", totalDocuments);

        // 2. 分块
        List<Document> allChunks = chunkingService.chunkDocuments(parentDocs);
        log.info("分块完成，共生成 {} 个文本块", allChunks.size());

        // 2.1 加载已保存的 BM25 文件，得到已处理 chunk 集合（作为权威记录）
        List<Document> savedChunks = DocumentStoreUtils.load(getBm25SaveFile());
        // 检测旧版索引格式：旧版本 chunk_id 为随机 UUID，每次启动都会变化，
        // 与 ParentChildManager 本次注册的映射不一致，导致检索后无法回溯父文档。
        // 发现过期格式时清空已处理记录，触发全量重建，一次性自愈。
        boolean staleIndex = savedChunks.stream().anyMatch(doc -> !isCanonicalChunkId(doc));
        if (staleIndex) {
            log.warn("检测到旧版索引格式（chunk_id 非稳定格式），将全量重建索引以修复父子文档映射");
            savedChunks = List.of();
        }
        Set<String> processedHashes = savedChunks.stream()
                .map(this::getContentHash)
                .collect(Collectors.toSet());
        log.info("已加载 {} 条已处理的 chunk 记录", processedHashes.size());

        // 3. 向量索引（增量构建）
        int newVectorCount = buildVectorIndex(allChunks, processedHashes, staleIndex);

        // 4. BM25 索引（增量合并 + 全量重建）
        int newBm25Count = buildBm25Index(allChunks, savedChunks, processedHashes);
        totalChunks = indexedChunks.size();

        // 5. 统计信息
        categoryStats = computeCategoryStats(parentDocs);
        difficultyStats = computeDifficultyStats(parentDocs);

        indexReady.set(true);
        long duration = System.currentTimeMillis() - startTime;
        log.info("知识库索引构建完成: {} 篇文档, {} 个文本块, 向量新增 {} 个, BM25 新增 {} 个, 耗时 {}ms",
                totalDocuments, totalChunks, newVectorCount, newBm25Count, duration);

        // 6. 异步持久化
        persistIndexAsync();

        return Map.of(
                "totalDocuments", totalDocuments,
                "totalChunks", totalChunks,
                "newVectorChunks", newVectorCount,
                "newBm25Chunks", newBm25Count,
                "durationMs", duration
        );
    }

    /**
     * 构建向量索引（增量）
     * <p>
     * 若向量存储是 SimpleVectorStore 且存在已保存文件：
     * - 直接加载已有向量数据（避免重复 embedding）
     * - 通过 content_hash 判定已处理的 chunk，跳过
     * - 仅对未处理的 chunk 执行 embedding 并加入向量库
     * <p>
     * 旧版索引（chunk_id 为随机 UUID）无法与 ParentChildManager 映射对应，
     * 此时忽略保存文件，全量重建向量索引。
     *
     * @param allChunks       本次加载的全部 chunk
     * @param processedHashes 已处理的 content_hash 集合
     * @param staleIndex      持久化索引是否为旧版格式（需要全量重建）
     * @return 实际新增的向量数量
     */
    private int buildVectorIndex(List<Document> allChunks, Set<String> processedHashes, boolean staleIndex) {
        // 尝试加载已保存的向量文件（旧版格式则跳过，避免旧 chunk_id 与新映射共存）
        File vectorFile = getVectorSaveFile();
        if (vectorStore instanceof SimpleVectorStore simpleStore && !staleIndex && vectorFile != null && vectorFile.exists()) {
            try {
                simpleStore.load(vectorFile);
                log.info("已加载向量文件: {}", vectorFile.getAbsolutePath());
            } catch (Exception e) {
                log.warn("加载向量文件失败，将全量构建向量索引: {}", e.getMessage());
            }
        } else {
            log.info("未找到已保存的向量文件，将全量构建向量索引");
        }

        // 过滤出未处理的 chunk
        List<Document> newChunks = allChunks.stream()
                .filter(chunk -> !processedHashes.contains(getContentHash(chunk)))
                .toList();

        if (newChunks.isEmpty()) {
            log.info("所有 {} 个 chunk 均已处理，跳过向量 embedding", allChunks.size());
        } else {
            vectorStore.add(newChunks);
            log.info("向量索引增量构建完成: 新增 {} 个 chunk (跳过 {} 个已处理)",
                    newChunks.size(), allChunks.size() - newChunks.size());
        }
        return newChunks.size();
    }

    /**
     * 构建 BM25 索引（增量合并 + 全量重建）
     * <p>
     * 将已保存的 BM25 chunk 与本次新增 chunk 合并（按 content_hash 去重），
     * 用完整集合重建 BM25（保证 IDF 全局统计正确）。
     *
     * @param allChunks       本次加载的全部 chunk
     * @param savedChunks     已保存的 chunk 列表
     * @param processedHashes 已处理的 content_hash 集合
     * @return 实际新增的 chunk 数量
     */
    private int buildBm25Index(List<Document> allChunks, List<Document> savedChunks, Set<String> processedHashes) {
        // 以 content_hash 为键合并去重
        Map<String, Document> merged = new LinkedHashMap<>();
        for (Document doc : savedChunks) {
            merged.putIfAbsent(getContentHash(doc), doc);
        }
        for (Document doc : allChunks) {
            merged.putIfAbsent(getContentHash(doc), doc);
        }

        List<Document> mergedChunks = new ArrayList<>(merged.values());
        int newCount = (int) allChunks.stream()
                .filter(doc -> !processedHashes.contains(getContentHash(doc)))
                .count();

        indexedChunks = mergedChunks;
        bm25Retriever = new Bm25Retriever(indexedChunks);
        log.info("BM25 索引重建完成: 共 {} 个 chunk (新增 {} 个, 跳过 {} 个)",
                indexedChunks.size(), newCount, allChunks.size() - newCount);
        return newCount;
    }

    /**
     * 异步持久化向量索引和 BM25 索引到本地文件
     */
    private void persistIndexAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                // 保存向量索引
                File vectorFile = getVectorSaveFile();
                if (vectorStore instanceof SimpleVectorStore simpleStore && vectorFile != null) {
                    vectorFile.getParentFile().mkdirs();
                    simpleStore.save(vectorFile);
                    log.info("向量索引已异步保存到: {}", vectorFile.getAbsolutePath());
                }

                // 保存 BM25 索引（保存全量 chunk 列表，供下次启动增量判定）
                File bm25File = getBm25SaveFile();
                if (bm25File != null && !indexedChunks.isEmpty()) {
                    DocumentStoreUtils.save(indexedChunks, bm25File);
                    log.info("BM25 索引已异步保存到: {}", bm25File.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("索引持久化失败: {}", e.getMessage(), e);
            }
        }, PERSIST_EXECUTOR);
    }

    /**
     * 获取向量保存文件
     */
    private File getVectorSaveFile() {
        RagProperties.SaveData save = properties.save();
        if (save == null || save.vectorSavePath() == null || save.vectorSavePath().isBlank()) {
            return null;
        }
        return new File(save.vectorSavePath());
    }

    /**
     * 获取 BM25 保存文件
     */
    private File getBm25SaveFile() {
        RagProperties.SaveData save = properties.save();
        if (save == null || save.bm25SavePath() == null || save.bm25SavePath().isBlank()) {
            return null;
        }
        return new File(save.bm25SavePath());
    }

    /**
     * 获取 chunk 的内容哈希（用于判定是否已处理）
     */
    private String getContentHash(Document doc) {
        Object hash = doc.getMetadata().get("content_hash");
        if (hash != null) {
            return hash.toString();
        }
        // 兜底：以内容 MD5 作为哈希
        return com.yybbglory.ai.eatagent.util.TextUtils.md5Hash(doc.getText());
    }

    /**
     * 判断 chunk 的 chunk_id 是否为稳定规范格式（由内容哈希派生）
     * <p>
     * 旧版本使用随机 UUID 作为 chunk_id，每次启动都会变化，
     * 与持久化索引及 ParentChildManager 映射无法对齐，此处用于识别并触发重建。
     *
     * @param doc chunk 文档
     * @return true 表示 chunk_id 是稳定的规范格式
     */
    private boolean isCanonicalChunkId(Document doc) {
        Object chunkId = doc.getMetadata().get("chunk_id");
        if (chunkId == null) {
            return false;
        }
        return chunkId.toString().equals("chunk-" + getContentHash(doc));
    }

    /**
     * 获取知识库统计信息
     */
    public KnowledgeStatsResponse getStats() {
        return new KnowledgeStatsResponse(
                totalDocuments,
                totalChunks,
                categoryStats,
                difficultyStats,
                indexReady.get()
        );
    }

    /**
     * 分页查询文档列表
     * 支持按分类、难度筛选，以及菜名关键词模糊匹配
     *
     * @param category   分类（可选，精确匹配中文标签）
     * @param difficulty 难度（可选，精确匹配）
     * @param keyword    菜名关键词（可选，模糊匹配，忽略大小写）
     * @param page       页码（从 1 开始）
     * @param size       每页条数
     * @return 分页结果
     */
    public PagedDocuments listDocuments(String category, String difficulty, String keyword, int page, int size) {
        List<Document> filtered = parentDocuments.stream()
                .filter(doc -> matchFilter(doc, category, difficulty, keyword))
                .toList();

        long total = filtered.size();
        int fromIndex = Math.min((page - 1) * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());

        List<DocumentSummary> items = filtered.subList(fromIndex, toIndex).stream()
                .map(this::toSummary)
                .toList();

        return new PagedDocuments(total, page, size, items);
    }

    /**
     * 获取文档详情（完整 Markdown 原文）
     * <p>
     * 返回前会将原文中相对路径的图片引用重写为绝对 URL，
     * 指向 {@code /api/knowledge/images/{parentId}/{文件名}}，保证前端可直接展示。
     *
     * @param parentId 父文档唯一标识
     * @return 文档详情
     * @throws BusinessException 文档不存在时抛出 404
     */
    public DocumentDetail getDocumentDetail(String parentId) {
        return parentDocuments.stream()
                .filter(doc -> parentId.equals(doc.getMetadata().get("parent_id")))
                .findFirst()
                .map(doc -> new DocumentDetail(
                        parentId,
                        (String) doc.getMetadata().getOrDefault("dish_name", "未知"),
                        (String) doc.getMetadata().getOrDefault("category", "未知"),
                        (String) doc.getMetadata().getOrDefault("difficulty", "未知"),
                        (String) doc.getMetadata().getOrDefault("source", ""),
                        rewriteImagePaths(doc.getText(), parentId)
                ))
                .orElseThrow(() -> new BusinessException(404, "文档不存在: " + parentId));
    }

    /**
     * 解析食谱图片资源
     * <p>
     * 食谱 markdown 中的图片为相对路径，与 .md 文件位于同一目录。
     * 通过 parent_id 反查 .md 文件位置（metadata.source），再解析目标图片。
     * 支持文件系统数据源与 classpath 数据源两种模式，并做路径穿越防护。
     *
     * @param parentId 父文档唯一标识
     * @param filename 图片文件名（相对路径）
     * @return 图片资源，不存在或越界时返回 null
     */
    public Resource resolveImage(String parentId, String filename) {
        if (parentId == null || parentId.isBlank() || filename == null || filename.isBlank()) {
            return null;
        }

        Document parent = parentDocuments.stream()
                .filter(doc -> parentId.equals(doc.getMetadata().get("parent_id")))
                .findFirst()
                .orElse(null);
        if (parent == null) {
            return null;
        }

        String source = (String) parent.getMetadata().get("source");
        if (source == null || source.isBlank()) {
            return null;
        }

        // 文件系统数据源：source 为绝对路径（如 /.../dishes/vegetable_dish/凉拌木耳/凉拌木耳.md）
        if (source.startsWith("/")) {
            Path mdPath = Paths.get(source);
            Path dir = mdPath.getParent();
            if (dir == null) {
                return null;
            }
            Path target = dir.resolve(filename).normalize();
            // 路径穿越防护：解析后的路径必须仍位于食谱目录内
            if (!target.startsWith(dir.normalize())) {
                log.warn("图片路径越界访问被拒绝: parentId={}, filename={}", parentId, filename);
                return null;
            }
            Resource resource = new FileSystemResource(target);
            return resource.exists() ? resource : null;
        }

        // classpath 数据源：source 为资源 URI（如 file:.../data/dishes/xxx.md），
        // 从 "dishes/" 之后截取相对路径
        int dishesIdx = source.indexOf("dishes/");
        if (dishesIdx < 0) {
            return null;
        }
        String relDir = source.substring(dishesIdx + "dishes/".length());
        Path relImage = Paths.get(relDir).getParent() != null
                ? Paths.get(relDir).getParent().resolve(filename).normalize()
                : Paths.get(filename).normalize();
        if (relImage.startsWith("..")) {
            log.warn("图片路径越界访问被拒绝: parentId={}, filename={}", parentId, filename);
            return null;
        }
        Resource resource = resourceLoader.getResource("classpath:data/dishes/" + relImage);
        return resource.exists() ? resource : null;
    }

    /**
     * 将 Markdown 原文中相对路径的图片引用重写为绝对 URL
     * <p>
     * 例如 {@code ![干木耳](1.jpg)} → {@code ![干木耳](/api/knowledge/images/{parentId}/1.jpg)}。
     * 已是绝对地址（http/https/data:/根路径）的引用保持不变。
     *
     * @param content  食谱 Markdown 原文
     * @param parentId 父文档唯一标识
     * @return 重写后的 Markdown 内容
     */
    String rewriteImagePaths(String content, String parentId) {
        if (content == null || content.isBlank() || parentId == null || parentId.isBlank()) {
            return content;
        }

        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String src = matcher.group(2);
            if (!isAbsoluteImageSrc(src)) {
                String encoded = UriUtils.encodePath(src, StandardCharsets.UTF_8);
                // 保留 title（如果有），例如 ![木耳](2.jpg "泡发后")
                matcher.appendReplacement(sb, Matcher.quoteReplacement(
                        "![" + matcher.group(1) + "](/api/knowledge/images/" + parentId + "/" + encoded + matcher.group(3) + ")"));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 判断图片引用是否为绝对地址（无需重写）
     */
    private boolean isAbsoluteImageSrc(String src) {
        return src.startsWith("http://")
                || src.startsWith("https://")
                || src.startsWith("data:")
                || src.startsWith("/")
                || src.contains("://");
    }

    /**
     * 判断文档是否满足筛选条件
     */
    private boolean matchFilter(Document doc, String category, String difficulty, String keyword) {
        if (category != null && !category.isBlank()) {
            Object cat = doc.getMetadata().get("category");
            if (cat == null || !cat.toString().equals(category)) {
                return false;
            }
        }
        if (difficulty != null && !difficulty.isBlank()) {
            Object diff = doc.getMetadata().get("difficulty");
            if (diff == null || !diff.toString().equals(difficulty)) {
                return false;
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            Object name = doc.getMetadata().get("dish_name");
            if (name == null || !name.toString().toLowerCase().contains(keyword.toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将文档转换为摘要 DTO
     */
    private DocumentSummary toSummary(Document doc) {
        return new DocumentSummary(
                (String) doc.getMetadata().getOrDefault("parent_id", ""),
                (String) doc.getMetadata().getOrDefault("dish_name", "未知"),
                (String) doc.getMetadata().getOrDefault("category", "未知"),
                (String) doc.getMetadata().getOrDefault("difficulty", "未知"),
                (String) doc.getMetadata().getOrDefault("source", "")
        );
    }

    /**
     * 检查索引是否就绪
     */
    public boolean isIndexReady() {
        return indexReady.get();
    }

    /**
     * 获取 BM25 检索器
     */
    public Bm25Retriever getBm25Retriever() {
        return bm25Retriever;
    }

    private Map<String, Integer> computeCategoryStats(List<Document> docs) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Document doc : docs) {
            String category = (String) doc.getMetadata().getOrDefault("category", "其他");
            stats.merge(category, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(stats);
    }

    private Map<String, Integer> computeDifficultyStats(List<Document> docs) {
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Document doc : docs) {
            String difficulty = (String) doc.getMetadata().getOrDefault("difficulty", "未知");
            stats.merge(difficulty, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(stats);
    }
}
