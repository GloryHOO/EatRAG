package com.yybbglory.ai.eatagent.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档列表持久化工具类
 * <p>
 * 用于将 {@link Document} 列表序列化到本地 JSON 文件，以及从 JSON 文件反序列化。
 * 序列化内容包含 id、text、metadata 三部分，可完整还原 Document。
 */
public final class DocumentStoreUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DocumentStoreUtils() {
        // 工具类禁止实例化
    }

    /**
     * 将文档列表保存到 JSON 文件
     * 若父目录不存在会自动创建
     *
     * @param docs 文档列表
     * @param file 目标文件
     */
    public static void save(List<Document> docs, File file) throws IOException {
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        List<Map<String, Object>> serializable = new ArrayList<>(docs.size());
        for (Document doc : docs) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", doc.getId());
            map.put("text", doc.getText());
            map.put("metadata", doc.getMetadata());
            serializable.add(map);
        }
        OBJECT_MAPPER.writeValue(file, serializable);
    }

    /**
     * 从 JSON 文件加载文档列表
     *
     * @param file 目标文件
     * @return 文档列表，文件不存在或解析失败时返回空列表
     */
    public static List<Document> load(File file) {
        if (file == null || !file.exists()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rawList = OBJECT_MAPPER.readValue(file,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Document> docs = new ArrayList<>(rawList.size());
            for (Map<String, Object> map : rawList) {
                String id = (String) map.get("id");
                String text = (String) map.get("text");
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) map.getOrDefault("metadata", Map.of());
                docs.add(new Document(id, text, metadata));
            }
            return docs;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 检查文件是否存在
     */
    public static boolean exists(File file) {
        return file != null && file.exists();
    }
}
