package com.zsir.mcpserver;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * @ClassName McpStdioClient
 * @description:
 * @author: zjj
 * @create: 2025-06-28 10:00
 **/
public class McpStdioClient {

    @Test
    public void stdio() {
        ServerParameters stdioParams = ServerParameters.builder("java")
                .args("-jar", "D:/016_Workspace/AI/mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar")
                .build();

        McpSyncClient mcpClient = McpClient.sync(new StdioClientTransport(stdioParams)).build();

        //初始化客户端连接
        mcpClient.initialize();

        //列出可用能力
        //McpSchema.ListToolsResult toolsList = mcpClient.listTools();

        //调用mcp 能力
        McpSchema.CallToolResult result = mcpClient.callTool(
                new McpSchema.CallToolRequest("getWeatherByCityNameAndDate",
                        Map.of("cityName", "乌鲁木齐", "date", "2025-06-28")));

        System.out.println("[mcp][stdio]执行结果：" + result);

        //关闭客户端
        mcpClient.closeGracefully();
    }

}
