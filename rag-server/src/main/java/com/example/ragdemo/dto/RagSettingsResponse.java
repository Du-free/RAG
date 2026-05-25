package com.example.ragdemo.dto;

public record RagSettingsResponse(
        // 当前生效的切块大小。
        int chunkSize,
        // 当前生效的切块重叠长度。
        int chunkOverlap,
        // 当前生效的向量召回数量。
        int topK,
        // 当前生效的相似度过滤阈值。
        double similarityThreshold,
        // 当前生效的重排序保留数量。
        int rerankTopN,
        // 当前是否启用 LLM 重排序。
        boolean rerankEnabled
) {
}
