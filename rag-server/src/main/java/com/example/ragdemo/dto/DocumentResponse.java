package com.example.ragdemo.dto;

import java.time.LocalDateTime;

public record DocumentResponse(
        Long id,
        String filename,
        String contentType,
        long fileSize,
        String status,
        int chunkCount,
        String errorMessage,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
