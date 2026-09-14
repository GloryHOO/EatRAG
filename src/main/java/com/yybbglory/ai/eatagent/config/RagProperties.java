package com.yybbglory.ai.eatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置属性类
 * 绑定 application.yml 中 eatagent.* 前缀的配置
 */
@ConfigurationProperties(prefix = "eatagent")
public record RagProperties(
        DataConfig data,
        RagConfig rag,
        RerankConfig rerank,
        SaveData save
) {
    /**
     * 数据源配置
     */
    public record DataConfig(
            /** 外部数据路径，为空时回退到 classpath:data/dishes/ */
            String path
    ) {}

    /**
     * RAG 核心参数配置
     */
    public record RagConfig(
            /** 最终返回结果数 */
            int topK,
            /** 过采样倍数 */
            int candidateMultiplier,
            /** RRF 平滑参数 */
            int rrfK,
            /** Rerank API 超时（毫秒） */
            int rerankTimeoutMs,
            /** 上下文最大长度 */
            int contextMaxLength,
            /** LLM 温度 */
            double llmTemperature,
            /** LLM 最大 token 数 */
            int llmMaxTokens
    ) {}

    /**
     * Rerank 模型配置
     */
    public record RerankConfig(
            /** DashScope API Key */
            String apiKey,
            /** Rerank 模型名称 */
            String model,
            /** Rerank API 基础 URL */
            String baseUrl
    ) {}

    public record SaveData(
            String vectorSavePath,
            String bm25SavePath
    ){ }

}
