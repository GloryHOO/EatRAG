package com.yybbglory.ai.eatagent.controller;

import com.yybbglory.ai.eatagent.model.ApiResponse;
import com.yybbglory.ai.eatagent.model.dto.DocumentDetail;
import com.yybbglory.ai.eatagent.model.dto.KnowledgeStatsResponse;
import com.yybbglory.ai.eatagent.model.dto.PagedDocuments;
import com.yybbglory.ai.eatagent.service.KnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * 知识库管理 API 控制器
 * 提供索引重建和统计信息查询端点
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 全量重建索引
     * POST /api/knowledge/rebuild
     */
    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Object>> rebuild() {
        log.info("收到索引重建请求");
        Map<String, Object> result = knowledgeService.buildIndex();
        return ApiResponse.success(result);
    }

    /**
     * 获取知识库统计信息
     * GET /api/knowledge/stats
     */
    @GetMapping("/stats")
    public ApiResponse<KnowledgeStatsResponse> stats() {
        KnowledgeStatsResponse stats = knowledgeService.getStats();
        return ApiResponse.success(stats);
    }

    /**
     * 分页查询文档列表
     * GET /api/knowledge/documents
     * 支持 category（分类）、difficulty（难度）、keyword（菜名关键词）筛选，page/size 分页
     */
    @GetMapping("/documents")
    public ApiResponse<PagedDocuments> documents(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
        PagedDocuments result = knowledgeService.listDocuments(category, difficulty, keyword, page, size);
        return ApiResponse.success(result);
    }

    /**
     * 获取文档详情（完整 Markdown 原文）
     * GET /api/knowledge/documents/{parentId}
     */
    @GetMapping("/documents/{parentId}")
    public ApiResponse<DocumentDetail> documentDetail(@PathVariable String parentId) {
        DocumentDetail detail = knowledgeService.getDocumentDetail(parentId);
        return ApiResponse.success(detail);
    }

    /**
     * 获取食谱图片（详情 markdown 中相对路径图片的解析目标）
     * GET /api/knowledge/images/{parentId}/{filename}
     */
    @GetMapping(value = "/images/{parentId}/{*filename}")
    public ResponseEntity<Resource> image(@PathVariable String parentId, @PathVariable String filename) {
        Resource resource = knowledgeService.resolveImage(parentId, filename);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(resource);
    }
}
