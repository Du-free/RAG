package com.example.ragdemo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class HealthController {

    /**
     * 简单健康检查接口，不依赖 MySQL 或 Qdrant，用于确认后端进程是否已经启动。
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "time", LocalDateTime.now()
        );
    }
}
