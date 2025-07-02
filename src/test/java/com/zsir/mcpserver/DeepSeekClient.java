package com.zsir.mcpserver;

import cn.hutool.core.convert.Convert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @ClassName DeepSeekClient（模拟AI客户端）
 * @description:
 * @author: zjj
 * @create: 2025-06-28 10:00
 **/
public class DeepSeekClient {

    private static McpSyncClient mcpClient;

    @BeforeAll
    public static void init() {
        //以sse模式为例，解析出mcp-tools列表
        mcpClient = McpClient.sync(new HttpClientSseClientTransport("http://localhost:8080")).build();
        mcpClient.initialize();
    }

    @Test
    public void run() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String tools = mapper.writeValueAsString(mcpClient.listTools().tools().parallelStream().map(model -> {
            Map<String, Object> function = new HashMap();
            function.put("name", model.name());
            function.put("description", model.description());
            function.put("parameters", model.inputSchema());
            Map<String, Object> tool = new HashMap();
            tool.put("type", "function");
            tool.put("function", function);
            return tool;
        }).collect(Collectors.toList()));
        System.out.println("①[Mcp-Client]解析能力列表\t\t\t\t" + tools);

        List<Map<String, Object>> execPlanList = getMcpToolCallFromDeepSeek(tools, "杭州 20250628 的温度是多少？");
        System.out.println("②[DeepSeek]解析自然语义返回规划\t\t" + execPlanList);

        String mcpResult = callMcpService(execPlanList);
        System.out.println("③[Mcp-Client]按顺序执行mcp调用结果\t\t" + mcpResult);

        String result = convertRawToNaturalLanguage(mcpResult);
        System.out.println("④[DeepSeek]将mcp调用结果转为自然语言\t" + result);
    }

    /***
     * @Description 调用模型api-chat，获取执行规划
     * @param: userInput
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/06/28 10:00
     */
    public List<Map<String, Object>> getMcpToolCallFromDeepSeek(String tools, String userInput) throws IOException {
        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json");

        // 构造 DeepSeek 请求（声明 McpService 能力）
        String requestBody = """
                {
                    "model": "deepseek-chat",
                    "messages": [{"role": "user", "content": "%s"}],
                    "tools": %s
                }
                """.formatted(userInput, tools);

        Request request = new Request.Builder().url("https://api.deepseek.com/v1/chat/completions").post(RequestBody.create(mediaType, requestBody))
                .addHeader("Authorization", "Bearer API 密钥").build();

        // 发送请求并解析响应
        try (Response response = client.newCall(request).execute()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode toolCalls = root.at("/choices/0/message/tool_calls");

            if (toolCalls.size() > 0) {
                String toolCallStr = Convert.toStr(toolCalls);
                return (List<Map<String, Object>>) mapper.readValue(toolCallStr, List.class)
                        .parallelStream().map(entity -> {
                            LinkedHashMap<String, Object> model = (LinkedHashMap) entity;
                            LinkedHashMap function = LinkedHashMap.class.cast(model.get("function"));
                            model.putAll(function);
                            model.remove("id");
                            model.remove("type");
                            model.remove("function");
                            return model;
                        }).sorted(Comparator.comparing(obj -> {
                            LinkedHashMap<String, Object> map = (LinkedHashMap) obj;
                            return Convert.toInt(map.get("index"));
                        })).collect(Collectors.toList());
            }
            return null;
        }
    }

    /***
     * @Description 执行MCP服务函数（stdio,sse）通过client端去模拟
     * @param: arguments
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/06/28 10:00
     */
    public String callMcpService(List<Map<String, Object>> execPlanList) throws IOException {
        String result = "";
        for (Map<String, Object> stepMap : execPlanList) {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder builder = new StringBuilder();
            String function = Convert.toStr(stepMap.get("name"));
            Map arguments = mapper.readValue(Convert.toStr(stepMap.get("arguments")), Map.class);
            //arguments多步骤处理判断，如果当前步骤包含step，需要重置值
            McpSchema.CallToolResult tool = mcpClient.callTool(new McpSchema.CallToolRequest(function, arguments));
            if (!tool.isError()) {
                List<McpSchema.Content> contents = tool.content();
                contents.forEach(ct -> {
                    //ct.type()
                    builder.append(McpSchema.TextContent.class.cast(ct).text());
                });
            }
            result = builder.toString();
        }
        return result;
    }

    /***
     * @Description 自然语言转换
     * @param: rawData
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/06/28 10:00
     */
    public String convertRawToNaturalLanguage(String rawData) throws IOException {
        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json");

        // 构造二次转换请求
        String requestBody = """
                {
                    "model": "deepseek-chat",
                    "messages": [
                        {"role": "user", "content": "请将以下内容简单转换为自然语言，不用写的很详细"},
                        {"role": "assistant", "content": %s}
                    ]
                }
                """.formatted(rawData);

        Request request = new Request.Builder().url("https://api.deepseek.com/v1/chat/completions").post(RequestBody.create(mediaType, requestBody))
                .addHeader("Authorization", "Bearer API 密钥").build();

        try (Response response = client.newCall(request).execute()) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body().string());
            return root.at("/choices/0/message/content").asText();
        }
    }

    @AfterAll
    public static void destory() {
        mcpClient.closeGracefully();
    }
}
