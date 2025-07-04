package com.zsir.mcpserver;

import cn.hutool.core.convert.Convert;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class DeepSeekClient {

    private static McpSyncClient mcpClient;

    private static List<Map<String, Object>> tools;

    @BeforeAll
    public static void init() throws JsonProcessingException {
        //以sse模式为例，解析出mcp-tools列表
        mcpClient = McpClient.sync(new HttpClientSseClientTransport("http://localhost:8080")).build();
        mcpClient.initialize();

        tools = mcpClient.listTools().tools().parallelStream()
                .map(model -> {
                    Map<String, Object> function = new HashMap();
                    function.put("name", model.name());
                    function.put("description", model.description());
                    function.put("parameters", model.inputSchema());
                    Map<String, Object> tool = new HashMap();
                    tool.put("type", "function");
                    tool.put("function", function);
                    return tool;
                }).collect(Collectors.toList());
        ObjectMapper mapper = new ObjectMapper();
        log.info("[能力列表初始化]解析能力列表进行注册\r\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
    }

    @Test
    public void chat() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String command = "deepseek账户余额是多少？";

        log.info("①[user]发送指令 [{}] 给 mcp-Client", command);

        List<Map<String, Object>> execPlanList = getMcpToolCallFromDeepSeek(mapper.writeValueAsString(tools), command);

        log.info("③[DeepSeek]经过语义分析，规划执行步骤，返回给mcp-client\r\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(execPlanList));

        String mcpResult = callMcpService(execPlanList);

        String result = convertRawToNaturalLanguage(mcpResult);
        log.info("⑥[DeepSeek]经过语义分析结果 [{}] 返回给mcp-client", result);

        log.info("⑦[mcp-client]将AI 模型分析后的结果返回给用户");
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

        log.info("②[mcp-client]将用户指令及注册的mcp能力列表发送给 AI 模型处理...\r\n" + requestBody);

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
        log.info("④[mcp-client]执行AI 模型返回的执行规划，按照index顺序调用能力执行...");
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
        //logmcp-client执行AI 模型返回的执行规划；按照index顺序调用能力执行
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
        log.info("⑤[mcp-client]将mcp-server执行结果 [{}] 再次发送AI 模型转换为自然语言", rawData);

        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json");

        // 构造二次转换请求
        String requestBody = """
                {
                    "model": "deepseek-chat",
                    "messages": [
                        {"role": "user", "content": "把回复改成一条简洁口语化的表达"},
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
