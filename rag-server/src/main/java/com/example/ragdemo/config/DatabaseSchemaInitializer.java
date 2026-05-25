package com.example.ragdemo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
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
            jdbcTemplate.execute("SELECT 1");
            log.info("RAG 数据库表初始化完成。");
        } catch (Exception ex) {
            log.warn("RAG 数据库暂时不可用，服务已继续启动；知识库和聊天接口会在数据库恢复前不可用。原因：{}；根因：{}",
                    ex.getMessage(),
                    getRootCauseMessage(ex));
        }
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
