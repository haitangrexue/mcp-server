package com.zsir.mcpserver;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName AppConfig
 * @description:
 * @author: zjj
 * @create: 2025/6/28 10:00
 **/
@Configuration
public class AppConfig {

    @Bean
    public ToolCallbackProvider tools(McpService mcpService) {
        return MethodToolCallbackProvider.builder().toolObjects(mcpService).build();
    }
}
