package com.example.ragdemo.dto;

import java.time.LocalDateTime;

public record EvaluationCaseResponse(
        Long id,
        String question,
        String expectedDocument,
        String referenceAnswer,
        String expectedKeywords,
        LocalDateTime createTime
) {
}
