package com.example.ragdemo.dto;

import java.util.List;

public record RagDebugResponse(
        String question,
        List<SourceResponse> candidates,
        List<SourceResponse> selectedSources,
        String answer
) {
}
