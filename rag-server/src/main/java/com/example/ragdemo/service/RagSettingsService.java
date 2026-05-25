package com.example.ragdemo.service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.dto.RagSettingsRequest;
import com.example.ragdemo.dto.RagSettingsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@RequiredArgsConstructor
public class RagSettingsService {

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties ragProperties;

    /**
     * 读取当前 RAG 参数；数据库中没有配置时使用 application.yaml 中的默认值。
     */
    public RagSettingsResponse getSettings() {
        Map<String, String> values = jdbcTemplate.query("""
                        SELECT setting_key, setting_value
                        FROM rag_runtime_setting
                        """, (rs, rowNum) -> Map.entry(rs.getString("setting_key"), rs.getString("setting_value")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return new RagSettingsResponse(
                intValue(values, "chunkSize", ragProperties.chunkSize()),
                intValue(values, "chunkOverlap", ragProperties.chunkOverlap()),
                intValue(values, "topK", ragProperties.topK()),
                doubleValue(values, "similarityThreshold", ragProperties.similarityThreshold()),
                intValue(values, "rerankTopN", ragProperties.rerankTopN()),
                booleanValue(values, "rerankEnabled", ragProperties.rerankEnabled())
        );
    }

    /**
     * 更新运行时参数；null 字段表示沿用当前值。
     */
    public RagSettingsResponse updateSettings(RagSettingsRequest request) {
        RagSettingsResponse current = getSettings();
        RagSettingsResponse next = new RagSettingsResponse(
                request.chunkSize() == null ? current.chunkSize() : request.chunkSize(),
                request.chunkOverlap() == null ? current.chunkOverlap() : request.chunkOverlap(),
                request.topK() == null ? current.topK() : request.topK(),
                request.similarityThreshold() == null ? current.similarityThreshold() : request.similarityThreshold(),
                request.rerankTopN() == null ? current.rerankTopN() : request.rerankTopN(),
                request.rerankEnabled() == null ? current.rerankEnabled() : request.rerankEnabled()
        );
        validate(next);
        save("chunkSize", String.valueOf(next.chunkSize()));
        save("chunkOverlap", String.valueOf(next.chunkOverlap()));
        save("topK", String.valueOf(next.topK()));
        save("similarityThreshold", String.valueOf(next.similarityThreshold()));
        save("rerankTopN", String.valueOf(next.rerankTopN()));
        save("rerankEnabled", String.valueOf(next.rerankEnabled()));
        return next;
    }

    private void validate(RagSettingsResponse settings) {
        if (settings.chunkSize() < 200 || settings.chunkSize() > 4000) {
            throw new ResponseStatusException(BAD_REQUEST, "chunkSize 需要在 200 到 4000 之间");
        }
        if (settings.chunkOverlap() < 0 || settings.chunkOverlap() >= settings.chunkSize()) {
            throw new ResponseStatusException(BAD_REQUEST, "chunkOverlap 需要大于等于 0 且小于 chunkSize");
        }
        if (settings.topK() < 1 || settings.topK() > 20) {
            throw new ResponseStatusException(BAD_REQUEST, "topK 需要在 1 到 20 之间");
        }
        if (settings.similarityThreshold() < 0 || settings.similarityThreshold() > 1) {
            throw new ResponseStatusException(BAD_REQUEST, "similarityThreshold 需要在 0 到 1 之间");
        }
        if (settings.rerankTopN() < 1 || settings.rerankTopN() > 20) {
            throw new ResponseStatusException(BAD_REQUEST, "rerankTopN 需要在 1 到 20 之间");
        }
    }

    private void save(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO rag_runtime_setting(setting_key, setting_value)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), update_time = CURRENT_TIMESTAMP
                """, key, value);
    }

    private int intValue(Map<String, String> values, String key, int defaultValue) {
        try {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private double doubleValue(Map<String, String> values, String key, double defaultValue) {
        try {
            return values.containsKey(key) ? Double.parseDouble(values.get(key)) : defaultValue;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private boolean booleanValue(Map<String, String> values, String key, boolean defaultValue) {
        return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
    }
}
