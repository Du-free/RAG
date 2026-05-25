package com.example.ragdemo.dto;

public record RagSettingsResponse(
        int chunkSize,
        int chunkOverlap,
        int topK,
        double similarityThreshold,
        int rerankTopN,
        boolean rerankEnabled
) {
}
