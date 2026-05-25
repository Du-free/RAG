package com.example.ragdemo.dto;

import java.time.LocalDateTime;
import java.util.List;

public record EvaluationRunResponse(
        Long id,
        int totalCases,
        double hitRate,
        double sourceCoverageRate,
        double answerKeywordRate,
        LocalDateTime createTime,
        List<EvaluationResultResponse> results
) {
}
