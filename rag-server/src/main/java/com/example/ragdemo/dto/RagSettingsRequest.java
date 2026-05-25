package com.example.ragdemo.dto;

public record RagSettingsRequest(
        Integer chunkSize,
        Integer chunkOverlap,
        Integer topK,
        Double similarityThreshold,
        Integer rerankTopN,
        Boolean rerankEnabled
) {
}
