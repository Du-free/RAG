package com.example.ragdemo.controller;

import com.example.ragdemo.dto.*;
import com.example.ragdemo.service.RagChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RagChatController {

    private final RagChatService ragChatService;

    /**
     * RAG 问答接口，返回答案和检索命中的来源片段。
     */
    @PostMapping("/api/chat/rag")
    public RagChatResponse ask(@RequestBody RagChatRequest request) {
        return ragChatService.ask(request);
    }

    /**
     * 检索调试接口：返回多路召回候选、重排序后的来源和最终答案，不保存聊天历史。
     */
    @PostMapping("/api/chat/debug")
    public RagDebugResponse debug(@RequestBody RagDebugRequest request) {
        return ragChatService.debug(request);
    }

    @GetMapping("/api/history/sessions")
    public List<String> listSessions() {
        return ragChatService.listSessions();
    }

    @GetMapping("/api/history/messages")
    public List<ChatMessageResponse> listMessages(@RequestParam String chatId) {
        return ragChatService.listMessages(chatId);
    }

    @DeleteMapping("/api/history/session")
    public void deleteSession(@RequestParam String chatId) {
        ragChatService.deleteSession(chatId);
    }
}
