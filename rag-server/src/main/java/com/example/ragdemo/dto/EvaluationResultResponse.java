package com.example.ragdemo.dto;

public record EvaluationResultResponse(
        Long caseId,
        String question,
        String answer,
        boolean hit,
        boolean sourceCovered,
        boolean keywordMatched,
        String expectedDocument,
        String matchedSources,
        String referenceAnswer
) {
}
