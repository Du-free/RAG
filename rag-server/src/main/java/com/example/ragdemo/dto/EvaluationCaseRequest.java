package com.example.ragdemo.dto;

public record EvaluationCaseRequest(
        String question,
        String expectedDocument,
        String referenceAnswer,
        String expectedKeywords
) {
}
