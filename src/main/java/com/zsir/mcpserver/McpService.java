package com.zsir.mcpserver;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * @ClassName McpService
 * @description:
 * @author: zjj
 * @create: 2025-06-28 10:00
 **/
@Service
public class McpService {

    /***
     * @Description 获取城市地区温度情况
     * @param: date     具体日期
     * @param: cityName 城市名称
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/6/28 10:00
     */
    @Tool(description = "获取城市地区温度情况，返回原始城市温度数据")
    public String getWeatherByCityNameAndDate(@ToolParam(description = "日期，日期格式为yyyy-MM-dd") String date,
                                              @ToolParam(description = "城市名称") String cityName) throws UnsupportedEncodingException {
        String city = new String(cityName.getBytes(Charset.defaultCharset()), "UTF-8");
        return city + " " + date + " 温度为 " + RandomUtil.randomInt(40) + " 摄氏度！";
    }

    /***
     * @Description 查询deepseek用户余额详情
     * @return: java.lang.String
     * @Author zjj
     * @Date 2025/6/30 19:23
     */
    @Tool(description = "查询deepseek用户余额详情，服务返回json字符串，需要按照官方接口解析出属性的意思")
    public String getDeepSeekBalance() throws IOException {
        OkHttpClient client = new OkHttpClient().newBuilder().build();
        Request request = new Request.Builder()
                .url("https://api.deepseek.com/user/balance")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer API 密钥")
                .build();
        Response response = client.newCall(request).execute();
        String resultkMsg = new String(response.body().bytes(), "UTF-8");
        return JSONUtil.parseObj(resultkMsg).getStr("balance_infos");
    }

}
