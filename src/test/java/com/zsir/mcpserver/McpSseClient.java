package com.zsir.mcpserver;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * @ClassName McpSseClient
 * @description:
 * @author: zjj
 * @create: 2025-06-29 19:51
 **/
public class McpSseClient {

    @Test
    public void sse() {
        McpSyncClient mcpClient = McpClient.sync(new HttpClientSseClientTransport("http://localhost:8080")).build();

        //初始化客户端连接
        mcpClient.initialize();

        //列出可用能力，可用于构造发送模型mcp信息
        //McpSchema.ListToolsResult toolsList = mcpClient.listTools();

        //调用mcp 能力
        McpSchema.CallToolResult balance = mcpClient.callTool(
                new McpSchema.CallToolRequest("getDeepSeekBalance", Map.of()));

        if (!balance.isError()) {
            List<McpSchema.Content> contents = balance.content();
            contents.forEach(ct -> {
                //ct.type()
                System.out.println("[mcp][sse]执行结果：" + McpSchema.TextContent.class.cast(ct).text());
            });
        }

        //关闭客户端
        mcpClient.closeGracefully();
    }

}
