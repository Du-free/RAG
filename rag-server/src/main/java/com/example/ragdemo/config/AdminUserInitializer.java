package com.example.ragdemo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时初始化管理员账号，并把升级前没有 user_id 的历史会话归属到该管理员。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AdminUserInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${RAG_ADMIN_USERNAME:admin}")
    private String adminUsername;

    @Value("${RAG_ADMIN_PASSWORD:admin123456}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long adminId = ensureAdminUser();
            jdbcTemplate.update("UPDATE rag_chat_session SET user_id = ? WHERE user_id IS NULL", adminId);
            jdbcTemplate.update("UPDATE rag_chat_message SET user_id = ? WHERE user_id IS NULL", adminId);
        } catch (Exception ex) {
            log.warn("管理员账号初始化失败，认证接口会在数据库恢复前不可用。原因：{}", ex.getMessage());
        }
    }

    private Long ensureAdminUser() {
        String username = StringUtils.hasText(adminUsername) ? adminUsername.trim() : "admin";
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_user WHERE role = 'ADMIN'",
                Integer.class
        );
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO rag_user(username, password_hash, role, enabled)
                    VALUES (?, ?, 'ADMIN', 1)
                    ON DUPLICATE KEY UPDATE
                        password_hash = VALUES(password_hash),
                        role = 'ADMIN',
                        enabled = 1
                    """, username, passwordEncoder.encode(adminPassword));
            log.info("已初始化 RAG 管理员账号：{}", username);
        }
        return jdbcTemplate.queryForObject("""
                SELECT id
                FROM rag_user
                WHERE role = 'ADMIN'
                ORDER BY id ASC
                LIMIT 1
                """, Long.class);
    }
}
