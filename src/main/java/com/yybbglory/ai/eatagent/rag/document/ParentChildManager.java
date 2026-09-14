package com.yybbglory.ai.eatagent.rag.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 父子文档管理器
 * <p>
 * 维护子文档块（chunk）到父文档的映射关系，以及父文档缓存。
 * 在检索阶段，根据命中的子块反查对应的完整父文档，
 * 并按命中次数降序排列，优先返回被多次命中的父文档。
 * <p>
 * 线程安全：内部使用 {@link ConcurrentHashMap} 存储映射和缓存。
 */
@Component
public class ParentChildManager {

    private static final Logger log = LoggerFactory.getLogger(ParentChildManager.class);

    /** 子块 ID → 父文档 ID 的映射 */
    private final Map<String, String> childToParentMap = new ConcurrentHashMap<>();

    /** 父文档 ID → 完整父文档的缓存 */
    private final Map<String, Document> parentDocCache = new ConcurrentHashMap<>();

    /**
     * 注册父文档到缓存
     * <p>
     * 以文档 metadata 中的 parent_id 作为键存储完整文档。
     *
     * @param parentDoc 父文档对象（metadata 中须包含 parent_id）
     */
    public void registerParent(Document parentDoc) {
        String parentId = (String) parentDoc.getMetadata().get("parent_id");
        if (parentId == null) {
            log.warn("父文档缺少 parent_id 元数据，跳过注册");
            return;
        }
        parentDocCache.put(parentId, parentDoc);
    }

    /**
     * 注册子块到父文档的映射关系
     *
     * @param childId  子块 ID（chunk_id）
     * @param parentId 父文档 ID（parent_id）
     */
    public void registerChild(String childId, String parentId) {
        childToParentMap.put(childId, parentId);
    }

    /**
     * 根据检索命中的子块列表，获取去重后的父文档
     * <p>
     * 返回结果按命中次数降序排列——被更多子块命中的父文档排在前面，
     * 表示该文档与查询的相关性更高。
     *
     * @param childChunks 检索命中的子块文档列表
     * @return 去重且按命中次数降序排列的父文档列表
     */
    public List<Document> getParentDocuments(List<Document> childChunks) {
        // 统计每个父文档的命中次数
        Map<String, Integer> hitCountMap = new LinkedHashMap<>();

        for (Document chunk : childChunks) {
            String chunkId = (String) chunk.getMetadata().get("chunk_id");
            if (chunkId == null) {
                continue;
            }

            String parentId = childToParentMap.get(chunkId);
            if (parentId != null) {
                hitCountMap.merge(parentId, 1, Integer::sum);
            }
        }

        // 按命中次数降序排列，获取对应的父文档
        return hitCountMap.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(entry -> parentDocCache.get(entry.getKey()))
                .filter(doc -> doc != null)
                .toList();
    }

    /**
     * 获取已注册的父文档数量
     *
     * @return 父文档缓存大小
     */
    public int getParentCount() {
        return parentDocCache.size();
    }

    /**
     * 获取已注册的子块映射数量
     *
     * @return 子块映射大小
     */
    public int getChildCount() {
        return childToParentMap.size();
    }

    /**
     * 清空所有映射和缓存
     * <p>
     * 通常在重新加载数据时调用。
     */
    public void clear() {
        childToParentMap.clear();
        parentDocCache.clear();
        log.info("父子文档管理器已清空");
    }
}
