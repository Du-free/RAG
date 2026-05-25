package com.example.ragdemo.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CommonConfiguration {

    /**
     * 创建统一的 ChatClient，集中设置系统角色，后续所有问答都复用该客户端。
     */
    @Bean
    public ChatClient chatClient(ZhiPuAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个专业、严谨的本地知识库问答助手。回答必须优先依据提供的知识库片段；如果片段不足以支持结论，请明确说明。")
                .build();
    }

    /**
     * 允许本地 Vite 前端访问后端接口，便于开发调试。
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        // Demo 开发阶段允许不同本地地址/端口的前端访问，避免 localhost、127.0.0.1 或局域网 IP 不一致导致 CORS 拦截。
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false);
            }
        };
    }
}
