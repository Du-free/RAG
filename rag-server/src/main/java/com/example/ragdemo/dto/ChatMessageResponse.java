package com.example.ragdemo.dto;

import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long id,
        String chatId,
        String role,
        String content,
        LocalDateTime createTime
) {
}
