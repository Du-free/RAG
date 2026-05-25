package com.example.ragdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String uploadDir,
        // 单个知识片段的目标最大长度；结构化切分会优先保留标题和段落，但仍用它控制片段上限。
        int chunkSize,
        // 超长段落使用滑窗切分时，相邻片段保留的重叠长度，用于避免关键信息被切断。
        int chunkOverlap,
        // 向量检索阶段希望返回的核心片段数量；实际召回会结合重排序参数适当放大候选池。
        int topK,
        // Qdrant 相似度过滤阈值；低于该分数的片段会被认为相关性不足。
        double similarityThreshold,
        // 大模型重排序后最终保留给回答生成的来源片段数量。
        int rerankTopN,
        // 是否启用大模型重排序；启用后答案来源更稳，但会增加一次模型调用。
        boolean rerankEnabled
) {
}
