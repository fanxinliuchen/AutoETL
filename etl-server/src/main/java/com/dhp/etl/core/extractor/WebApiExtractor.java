package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlPipeline;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.*;

/**
 * WebAPI 数据源抽取组件 (含 JsonPath 解析与 SSRF 防护)
 */
@Slf4j
@Component
public class WebApiExtractor {

    private static final List<String> BLOCKED_IP_PREFIXES = List.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0.", "169.254."
    );

    /**
     * 抽取 WebAPI 结果数据
     */
    public List<Map<String, Object>> extract(EtlPipeline pipeline, StringBuilder logBuffer) {
        String apiUrl = pipeline.getApiUrl();
        if (StrUtil.isBlank(apiUrl)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "WebAPI URL 不能为空");
        }

        // SSRF 防护校验
        validateUrlSafety(apiUrl);

        String method = StrUtil.blankToDefault(pipeline.getApiMethod(), "GET").toUpperCase();
        String jsonPath = StrUtil.blankToDefault(pipeline.getApiDataPath(), "$");

        // 解析主请求头
        Map<String, String> headers = new HashMap<>();
        if (StrUtil.isNotBlank(pipeline.getApiHeaders())) {
            try {
                JSONObject headerObj = JSONUtil.parseObj(pipeline.getApiHeaders());
                headerObj.forEach((k, v) -> headers.put(k, String.valueOf(v)));
            } catch (Exception e) {
                log.warn("解析请求头异常: {}", e.getMessage());
            }
        }

        // ===== 步骤一: 执行前置登录鉴权 (金蝶 K/3 Cloud 会话凭证或自定义登录) =====
        executePreAuthIfConfigured(pipeline, headers, logBuffer);

        logBuffer.append(String.format("[WebAPI] 数据拉取地址: %s, 方式: %s\n", apiUrl, method));

        HttpRequest request = "POST".equals(method) ? HttpRequest.post(apiUrl) : HttpRequest.get(apiUrl);
        if (!headers.isEmpty()) {
            request.addHeaders(headers);
        }
        if ("POST".equals(method) && StrUtil.isNotBlank(pipeline.getApiBody())) {
            request.body(pipeline.getApiBody());
        }

        try (HttpResponse response = request.timeout(30000).execute()) {
            if (!response.isOk()) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "WebAPI 响应异常，HTTP状态码: " + response.getStatus());
            }

            String body = response.body();
            logBuffer.append(String.format("[WebAPI] 响应成功，数据长度: %d 字符\n", body != null ? body.length() : 0));

            // 执行 JsonPath 提取
            Object extracted = JsonPath.read(body, jsonPath);
            List<Map<String, Object>> records = new ArrayList<>();

            // 尝试从请求体解析金蝶 FieldKeys，用于二维数组还原列名
            List<String> fieldKeys = parseFieldKeysFromRequestBody(pipeline.getApiBody());

            if (extracted instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        map.forEach((k, v) -> record.put(String.valueOf(k), v));
                        records.add(record);
                    } else if (item instanceof List<?> rowList) {
                        // 金蝶 ExecuteBillQuery 常返回二维数组: [ [1, "A"], [2, "B"] ]
                        Map<String, Object> record = new LinkedHashMap<>();
                        for (int i = 0; i < rowList.size(); i++) {
                            String colName = (fieldKeys != null && i < fieldKeys.size()) ? fieldKeys.get(i) : "col_" + i;
                            record.put(colName, rowList.get(i));
                        }
                        records.add(record);
                    }
                }
            } else if (extracted instanceof Map<?, ?> map) {
                Map<String, Object> record = new LinkedHashMap<>();
                map.forEach((k, v) -> record.put(String.valueOf(k), v));
                records.add(record);
            }

            logBuffer.append(String.format("[WebAPI] JsonPath (%s) 抽取解析得到: %d 条记录\n", jsonPath, records.size()));
            return records;
        } catch (BizException be) {
            throw be;
        } catch (Exception ex) {
            log.error("WebAPI 请求执行失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "WebAPI 抽取失败: " + ex.getMessage());
        }
    }

    /**
     * 前置登录鉴权执行逻辑 (获取 Cookie 或 Token 并注入请求头)
     */
    public Map<String, Object> executePreAuth(String authType, String authConfigJson, StringBuilder logBuffer) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (StrUtil.isBlank(authType) || "NONE".equalsIgnoreCase(authType) || StrUtil.isBlank(authConfigJson)) {
            result.put("success", true);
            result.put("message", "无前置鉴权配置");
            return result;
        }

        try {
            JSONObject authConfig = JSONUtil.parseObj(authConfigJson);
            String loginUrl = authConfig.getStr("loginUrl");
            if (StrUtil.isBlank(loginUrl)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "登录接口 URL 不能为空");
            }
            validateUrlSafety(loginUrl);

            String loginMethod = StrUtil.blankToDefault(authConfig.getStr("loginMethod"), "POST").toUpperCase();
            String loginBody = authConfig.getStr("loginBody");
            String loginHeadersStr = authConfig.getStr("loginHeaders");

            // 金蝶云星空模式: 若未传原始 body，则根据参数自动组装金蝶标准登录体
            if ("KINGDEE_K3".equalsIgnoreCase(authType) && StrUtil.isBlank(loginBody) && authConfig.containsKey("acctID")) {
                JSONObject kingdeeBody = new JSONObject();
                kingdeeBody.set("acctID", authConfig.getStr("acctID"));
                kingdeeBody.set("userName", authConfig.getStr("userName"));
                kingdeeBody.set("password", authConfig.getStr("password"));
                kingdeeBody.set("lcid", authConfig.getInt("lcid", 2052));
                loginBody = kingdeeBody.toString();
            }

            if (logBuffer != null) {
                logBuffer.append(String.format("[WebAPI-Auth] 正在向登录接口发送鉴权请求: %s\n", loginUrl));
            }

            HttpRequest authReq = "POST".equals(loginMethod) ? HttpRequest.post(loginUrl) : HttpRequest.get(loginUrl);
            authReq.header("Content-Type", "application/json");
            if (StrUtil.isNotBlank(loginHeadersStr)) {
                try {
                    JSONObject lHeaders = JSONUtil.parseObj(loginHeadersStr);
                    lHeaders.forEach((k, v) -> authReq.header(k, String.valueOf(v)));
                } catch (Exception ignored) {}
            }
            if ("POST".equals(loginMethod) && StrUtil.isNotBlank(loginBody)) {
                authReq.body(loginBody);
            }

            try (HttpResponse authResp = authReq.timeout(30000).execute()) {
                result.put("status", authResp.getStatus());
                String respBody = authResp.body();
                result.put("responseBody", respBody);

                if (!authResp.isOk()) {
                    String errMsg = "前置登录失败，HTTP 状态码: " + authResp.getStatus();
                    result.put("success", false);
                    result.put("message", errMsg);
                    if (logBuffer != null) logBuffer.append("[WebAPI-Auth] " + errMsg + "\n");
                    return result;
                }

                // 金蝶云星空业务层登录结果校验 (LoginResultType: 1-成功, -1/-2/0-失败)
                if ("KINGDEE_K3".equalsIgnoreCase(authType) && StrUtil.isNotBlank(respBody)) {
                    try {
                        JSONObject respJson = JSONUtil.parseObj(respBody);
                        Integer resultType = respJson.getInt("LoginResultType");
                        if (resultType != null && resultType != 1) {
                            String msg = respJson.getStr("Message");
                            result.put("success", false);
                            result.put("message", "金蝶登录验证未通过: " + (StrUtil.isNotBlank(msg) ? msg : "LoginResultType=" + resultType));
                            if (logBuffer != null) logBuffer.append("[WebAPI-Auth] " + result.get("message") + "\n");
                            return result;
                        }
                    } catch (Exception ignored) {}
                }

                // 1. 提取 Set-Cookie (包含 kdservice-sessionid 等)
                List<String> setCookies = authResp.headerList("Set-Cookie");
                StringBuilder cookieBuf = new StringBuilder();
                if (setCookies != null) {
                    for (String sc : setCookies) {
                        String single = sc.split(";")[0].trim();
                        if (cookieBuf.length() > 0) cookieBuf.append("; ");
                        cookieBuf.append(single);
                    }
                }
                String cookieStr = cookieBuf.toString();
                result.put("cookie", cookieStr);

                // 2. 尝试从响应体提取 token (若配置了 extractPath)
                String tokenPath = authConfig.getStr("extractPath");
                if (StrUtil.isNotBlank(tokenPath)) {
                    try {
                        Object tokenVal = JsonPath.read(respBody, tokenPath);
                        if (tokenVal != null) {
                            result.put("token", String.valueOf(tokenVal));
                        }
                    } catch (Exception ignored) {}
                }

                result.put("success", true);
                result.put("message", "登录成功！已成功捕获会话 Cookie (KDS)。");
                if (logBuffer != null) {
                    logBuffer.append(String.format("[WebAPI-Auth] 登录成功，会话 Cookie: %s\n", StrUtil.brief(cookieStr, 60)));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("前置登录失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "登录异常: " + e.getMessage());
            if (logBuffer != null) logBuffer.append("[WebAPI-Auth] 登录异常: " + e.getMessage() + "\n");
            return result;
        }
    }

    private void executePreAuthIfConfigured(EtlPipeline pipeline, Map<String, String> headers, StringBuilder logBuffer) {
        if (StrUtil.isNotBlank(pipeline.getApiAuthType()) && !"NONE".equalsIgnoreCase(pipeline.getApiAuthType())) {
            Map<String, Object> authRes = executePreAuth(pipeline.getApiAuthType(), pipeline.getApiAuthConfig(), logBuffer);
            if (!Boolean.TRUE.equals(authRes.get("success"))) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "前置认证登录失败: " + authRes.get("message"));
            }
            String cookie = (String) authRes.get("cookie");
            if (StrUtil.isNotBlank(cookie)) {
                String existing = headers.get("Cookie");
                headers.put("Cookie", StrUtil.isNotBlank(existing) ? existing + "; " + cookie : cookie);
            }
            String token = (String) authRes.get("token");
            if (StrUtil.isNotBlank(token)) {
                headers.putIfAbsent("Authorization", "Bearer " + token);
            }
        }
    }

    private List<String> parseFieldKeysFromRequestBody(String body) {
        if (StrUtil.isBlank(body)) return null;
        try {
            JSONObject obj = JSONUtil.parseObj(body);
            JSONObject dataObj = obj.getJSONObject("data");
            String fieldKeysStr = dataObj != null ? dataObj.getStr("FieldKeys") : obj.getStr("FieldKeys");
            if (StrUtil.isNotBlank(fieldKeysStr)) {
                return Arrays.stream(fieldKeysStr.split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .toList();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void validateUrlSafety(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (StrUtil.isBlank(host)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "URL 缺少有效主机域名");
            }
            // 内部企业 ERP (10.x, 192.168.x, 172.x) 予以放行，仅拦截云厂商元数据服务 169.254.169.254
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if ("169.254.169.254".equals(ip)) {
                throw new BizException(ErrorCode.ETL_SQL_SAFETY_ERROR, "安全拦截: 禁止访问云厂商元数据服务");
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无效的 WebAPI 地址: " + e.getMessage());
        }
    }
}
