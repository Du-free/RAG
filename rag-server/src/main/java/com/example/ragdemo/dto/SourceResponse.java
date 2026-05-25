package com.example.ragdemo.dto;

public record SourceResponse(
        Long documentId,
        String filename,
        String chunkId,
        String snippet,
        Double score,
        Double rerankScore,
        Integer rank,
        String reason,
        String stage,
        String sectionTitle
) {
}
