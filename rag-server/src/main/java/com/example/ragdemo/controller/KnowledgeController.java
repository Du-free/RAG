package com.example.ragdemo.controller;

import com.example.ragdemo.dto.DocumentResponse;
import com.example.ragdemo.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 上传文档到默认知识库。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentResponse upload(@RequestParam("file") MultipartFile file) {
        return knowledgeService.upload(file);
    }

    /**
     * 查询默认知识库中的文档列表。
     */
    @GetMapping("/documents")
    public List<DocumentResponse> listDocuments() {
        return knowledgeService.listDocuments();
    }

    /**
     * 删除文档对应的向量和元数据；前端会在调用前要求用户确认。
     */
    @DeleteMapping("/documents/{id}")
    public void deleteDocument(@PathVariable Long id) {
        knowledgeService.deleteDocument(id);
    }
}
