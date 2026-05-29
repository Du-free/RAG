package com.example.ragdemo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class DatabaseSchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动后尝试初始化数据库表。MySQL 不可用时只记录日志，不阻断后端启动。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            populator.execute(dataSource);
            applyCompatibleMigrations();
            jdbcTemplate.execute("SELECT 1");
            log.info("RAG 数据库表初始化完成。");
        } catch (Exception ex) {
            log.warn("RAG 数据库暂时不可用，服务已继续启动；知识库和聊天接口会在数据库恢复前不可用。原因：{}；根因：{}",
                    ex.getMessage(),
                    getRootCauseMessage(ex));
        }
    }

    /**
     * 为旧库补齐后续版本新增的列和索引，避免 CREATE TABLE IF NOT EXISTS 无法修改已有表结构。
     */
    private void applyCompatibleMigrations() {
        if (!columnExists("rag_document_chunk", "section_title")) {
            jdbcTemplate.execute("""
                    ALTER TABLE rag_document_chunk
                    ADD COLUMN section_title VARCHAR(255) NULL COMMENT '分块所属章节标题'
                    AFTER content
                    """);
        }
        if (!columnExists("rag_document_chunk", "split_strategy")) {
            jdbcTemplate.execute("""
                    ALTER TABLE rag_document_chunk
                    ADD COLUMN split_strategy VARCHAR(64) NULL COMMENT '分块策略，如 paragraph/table/code'
                    AFTER section_title
                    """);
        }
        if (!columnExists("rag_chat_session", "user_id")) {
            jdbcTemplate.execute("""
                    ALTER TABLE rag_chat_session
                    ADD COLUMN user_id BIGINT NULL COMMENT '用户ID'
                    AFTER chat_id
                    """);
        }
        if (!indexExists("rag_chat_session", "idx_rag_session_user_id")) {
            jdbcTemplate.execute("CREATE INDEX idx_rag_session_user_id ON rag_chat_session(user_id)");
        }
        if (!columnExists("rag_chat_message", "user_id")) {
            jdbcTemplate.execute("""
                    ALTER TABLE rag_chat_message
                    ADD COLUMN user_id BIGINT NULL COMMENT '用户ID'
                    AFTER id
                    """);
        }
        if (!indexExists("rag_chat_message", "idx_rag_chat_user_id")) {
            jdbcTemplate.execute("CREATE INDEX idx_rag_chat_user_id ON rag_chat_message(user_id)");
        }
        if (!indexExists("rag_document_chunk", "ft_rag_chunk_content")) {
            try {
                jdbcTemplate.execute("CREATE FULLTEXT INDEX ft_rag_chunk_content ON rag_document_chunk(content)");
            } catch (DataAccessException ex) {
                // 全文索引是增强能力；创建失败时保留 LIKE 兜底召回，不阻断服务启动。
                log.warn("RAG 全文索引创建失败，后续将使用 LIKE 兜底召回。原因：{}", ex.getMessage());
            }
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    /**
     * 展开 Spring SQL 初始化异常的根因，避免日志只显示 Failed to execute database script 这种外层包装信息。
     */
    private String getRootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
