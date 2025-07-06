package com.zsir.mcpserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @ClassName DeepSeekClient
 * @description:
 * @author: zjj
 * @create: 2025-06-28 10:00
 **/
@Slf4j
public class DeepSeekClient {

    private static McpSyncClient mcpClient;

    private static List<Map<String, Object>> tools;

    /***
     * @Description 初始化MCP-CLIENT
     * @Author zjj
     * @Date 2025-06-28 10:00
     */
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
        log.info("[mcp-server]注册到mcp-host并解析能力列表\r\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
    }

    @Test
    public void chat() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String command = "deepseek账户余额是多少？";

        log.info("①[user]发送指令 [{}] 给 mcp-host", command);

        List<Map<String, Object>> execPlanList = getMcpToolCallFromDeepSeek(mapper.writeValueAsString(tools), command);

        log.info("③[DeepSeek]结合上下文信息经过语义分析，整合工具规划执行步骤，返回给mcp-host\r\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(execPlanList));

        String mcpResult = callMcpService(execPlanList);

        String result = convertRawToNaturalLanguage(mcpResult);
        log.info("⑥[DeepSeek]经过语义分析结果 [{}] 返回给mcp-host", result);

        log.info("⑦[mcp-host]将DeepSeek分析后的结果返回给用户");
    }

    /***
     * @Description 调用模型api-chat，获取执行规划
     * @param: userInput
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/06/28 10:00
     */
    public List<Map<String, Object>> getMcpToolCallFromDeepSeek(String tools, String userInput) throws IOException {
        // 构造 DeepSeek 请求（声明 McpService 能力）
        String requestBody = """
                {
                    "model": "deepseek-chat",
                    "messages": [{"role": "user", "content": "%s"}],
                    "tools": %s
                }
                """.formatted(userInput, tools);

        RestClient restClient = RestClient.builder().baseUrl("https://api.deepseek.com/v1/chat/completions")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer API密钥").build();
        String responseBody = restClient.post().body(requestBody).retrieve().body(String.class);

        log.info("②[mcp-host]将用户指令及已注册的mcp能力列表发送给DeepSeek进行处理...\r\n" + requestBody);

        // 发送请求并解析响应
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);
        JsonNode toolCalls = root.at("/choices/0/message/tool_calls");

        if (toolCalls.size() > 0) {
            return (List<Map<String, Object>>) mapper.readValue(toolCalls.toString(), List.class)
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
                        return (Integer) map.get("index");
                    })).collect(Collectors.toList());
        }
        return null;
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
        log.info("④[mcp-host]通过mcp-client调用mcp-server按DeepSeek返回的步骤进行顺序执行...");
        for (Map<String, Object> stepMap : execPlanList) {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder builder = new StringBuilder();
            String function = String.valueOf(stepMap.get("name"));
            Map arguments = mapper.readValue(String.valueOf(stepMap.get("arguments")), Map.class);
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
        log.info("⑤[mcp-host]将mcp-server返回结果 [{}] 再次发送DeepSeek转义为自然语言", rawData);

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

        RestClient restClient = RestClient.builder().baseUrl("https://api.deepseek.com/v1/chat/completions")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer API密钥").build();
        String responseBody = restClient.post().body(requestBody).retrieve().body(String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);
        return root.at("/choices/0/message/content").asText();
    }

    /***
     * @Description 关闭MCP-CLIENT
     * @Author zjj
     * @Date 2025-06-28 10:00
     */
    @AfterAll
    public static void destory() {
        mcpClient.closeGracefully();
    }
}
