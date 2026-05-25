package com.example.ragdemo.controller;

import com.example.ragdemo.dto.RagSettingsRequest;
import com.example.ragdemo.dto.RagSettingsResponse;
import com.example.ragdemo.service.RagSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/settings")
@RequiredArgsConstructor
public class RagSettingsController {

    private final RagSettingsService ragSettingsService;

    @GetMapping
    public RagSettingsResponse getSettings() {
        return ragSettingsService.getSettings();
    }

    @PutMapping
    public RagSettingsResponse updateSettings(@RequestBody RagSettingsRequest request) {
        return ragSettingsService.updateSettings(request);
    }
}
