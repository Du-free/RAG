package com.example.ragdemo.dto;

import java.time.LocalDateTime;

/**
 * 历史会话列表项，用于前端展示可读标题而不是随机 chatId。
 */
public record ChatSessionResponse(
        String chatId,
        String title,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
