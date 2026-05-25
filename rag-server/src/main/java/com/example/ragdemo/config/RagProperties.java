package com.example.ragdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String uploadDir,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double similarityThreshold,
        int rerankTopN,
        boolean rerankEnabled
) {
}
