package com.yybbglory.ai.eatagent.rag.document;

import com.yybbglory.ai.eatagent.config.RagProperties;
import com.yybbglory.ai.eatagent.model.enums.Category;
import com.yybbglory.ai.eatagent.model.enums.Difficulty;
import com.yybbglory.ai.eatagent.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 食谱数据加载器
 * <p>
 * 负责扫描数据目录中的 Markdown 食谱文件，读取内容并提取元数据，
 * 返回 Spring AI {@link Document} 列表供后续分块和索引使用。
 * <p>
 * 数据目录结构约定：
 * <pre>
 * dishes/&lt;category_dir&gt;/&lt;dish_name&gt;.md
 * dishes/&lt;category_dir&gt;/&lt;dish_name&gt;/&lt;dish_name&gt;.md
 * </pre>
 * <p>
 * 支持外部文件系统路径优先、classpath 回退两种数据源模式。
 */
@Component
public class RecipeLoader {

    private static final Logger log = LoggerFactory.getLogger(RecipeLoader.class);

    /** Markdown 文件后缀 */
    private static final String MD_EXTENSION = ".md";

    /** classpath 下的默认数据目录前缀 */
    private static final String CLASSPATH_DATA_PREFIX = "classpath:data/dishes/";

    private final RagProperties ragProperties;

    public RecipeLoader(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 加载所有食谱文档
     * <p>
     * 优先从外部文件系统路径加载，若未配置或路径不存在则回退到 classpath。
     *
     * @return 包含内容和元数据的 Document 列表
     */
    public List<Document> loadRecipes() {
        String externalPath = ragProperties.data() != null ? ragProperties.data().path() : null;

        // 优先尝试外部文件系统路径
        if (externalPath != null && !externalPath.isBlank()) {
            Path dataRoot = Paths.get(externalPath);
            if (Files.isDirectory(dataRoot)) {
                log.info("从外部路径加载食谱数据: {}", dataRoot);
                return loadFromFileSystem(dataRoot);
            } else {
                log.warn("外部数据路径不存在或不是目录: {}，将回退到 classpath", externalPath);
            }
        }

        // 回退到 classpath
        log.info("从 classpath 加载食谱数据");
        return loadFromClasspath();
    }

    /**
     * 从外部文件系统递归加载 Markdown 文件
     *
     * @param dataRoot 数据根目录（如 dishes/）
     * @return Document 列表
     */
    private List<Document> loadFromFileSystem(Path dataRoot) {
        List<Document> documents = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(dataRoot)) {
            paths.filter(p -> p.toString().endsWith(MD_EXTENSION))
                    .filter(Files::isRegularFile)
                    .forEach(mdFile -> {
                        try {
                            Document doc = buildDocument(mdFile, dataRoot);
                            if (doc != null) {
                                documents.add(doc);
                            }
                        } catch (IOException e) {
                            log.error("读取食谱文件失败: {}", mdFile, e);
                        }
                    });
        } catch (IOException e) {
            log.error("遍历数据目录失败: {}", dataRoot, e);
        }

        log.info("从文件系统加载了 {} 个食谱文档", documents.size());
        return documents;
    }

    /**
     * 从 classpath 加载 Markdown 文件
     *
     * @return Document 列表
     */
    private List<Document> loadFromClasspath() {
        List<Document> documents = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            // 匹配 classpath:data/dishes/ 下所有 .md 文件
            Resource[] resources = resolver.getResources(CLASSPATH_DATA_PREFIX + "**/*" + MD_EXTENSION);

            for (Resource resource : resources) {
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    String uri = resource.getURI().toString();

                    // 从 URI 中提取相对路径用于元数据解析
                    String relativePath = extractClasspathRelativePath(uri);
                    Map<String, Object> metadata = buildMetadata(relativePath, content);
                    metadata.put("source", uri);

                    Document doc = new Document(content, metadata);
                    documents.add(doc);
                } catch (IOException e) {
                    log.error("读取 classpath 食谱文件失败: {}", resource, e);
                }
            }
        } catch (IOException e) {
            log.error("扫描 classpath 数据目录失败", e);
        }

        log.info("从 classpath 加载了 {} 个食谱文档", documents.size());
        return documents;
    }

    /**
     * 根据 Markdown 文件构建 Document 对象
     *
     * @param mdFile   Markdown 文件路径
     * @param dataRoot 数据根目录
     * @return Document 对象，读取失败返回 null
     */
    private Document buildDocument(Path mdFile, Path dataRoot) throws IOException {
        String content = Files.readString(mdFile, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            log.warn("跳过空文件: {}", mdFile);
            return null;
        }

        // 计算相对于数据根目录的路径
        Path relativePath = dataRoot.relativize(mdFile);
        Map<String, Object> metadata = buildMetadata(relativePath.toString(), content);
        metadata.put("source", mdFile.toString());

        return new Document(content, metadata);
    }

    /**
     * 从相对路径和内容中提取食谱元数据
     * <p>
     * 元数据字段：
     * <ul>
     *   <li>dish_name - 菜名（文件名去除扩展名）</li>
     *   <li>category - 分类（从父目录名映射）</li>
     *   <li>difficulty - 难度等级（从 ★ 数量解析）</li>
     *   <li>parent_id - 父文档 ID（相对路径的 MD5 哈希）</li>
     *   <li>doc_type - 文档类型标记（"parent"）</li>
     * </ul>
     *
     * @param relativePath 相对于数据根目录的文件路径
     * @param content      Markdown 文件内容
     * @return 元数据 Map
     */
    private Map<String, Object> buildMetadata(String relativePath, String content) {
        Map<String, Object> metadata = new HashMap<>();

        Path path = Paths.get(relativePath);
        int nameCount = path.getNameCount();

        // 提取菜名：文件名去掉 .md 后缀
        String fileName = path.getFileName().toString();
        String dishName = fileName.substring(0, fileName.length() - MD_EXTENSION.length());
        metadata.put("dish_name", dishName);

        // 提取分类：第一级目录名映射到 Category 枚举
        if (nameCount >= 2) {
            String categoryDir = path.getName(0).toString();
            Category category = Category.fromDirName(categoryDir);
            metadata.put("category", category.getLabel());
        } else {
            metadata.put("category", Category.OTHER.getLabel());
        }

        // 提取难度：从内容中解析 ★ 数量
        Difficulty difficulty = Difficulty.fromContent(content);
        metadata.put("difficulty", difficulty.getLabel());

        // 生成 parent_id：使用相对路径的 MD5 哈希作为唯一标识
        String parentId = TextUtils.md5Hash(relativePath);
        metadata.put("parent_id", parentId);

        // 标记为父文档
        metadata.put("doc_type", "parent");

        return metadata;
    }

    /**
     * 从 classpath URI 中提取相对路径
     * <p>
     * 例如：file:/.../classes/data/dishes/meat_dish/可乐鸡翅.md → meat_dish/可乐鸡翅.md
     *
     * @param uri 资源 URI 字符串
     * @return 相对于 dishes/ 目录的路径
     */
    private String extractClasspathRelativePath(String uri) {
        // 查找 "dishes/" 关键字并截取其后的部分
        int idx = uri.indexOf("dishes/");
        if (idx >= 0) {
            return uri.substring(idx + "dishes/".length());
        }
        // 兜底：返回文件名
        int lastSlash = uri.lastIndexOf('/');
        return lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
    }
}
