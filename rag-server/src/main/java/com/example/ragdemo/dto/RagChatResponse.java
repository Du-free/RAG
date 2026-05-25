package com.example.ragdemo.dto;

import java.util.List;

public record RagChatResponse(
        String chatId,
        String answer,
        List<SourceResponse> sources
) {
}
