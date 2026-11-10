package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WebApiExtractor {

    private final EtlKingdeeCredentialService kingdeeCredentialService;
    private final RequestVarResolver requestVarResolver;

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

        // 变量在后端解析：内置 ${today}/${yesterday}/${now}，以及过滤条件里定义的 SQL 变量
        String requestBody = requestVarResolver.resolve(pipeline, pipeline.getApiBody(), logBuffer);
        if (StrUtil.isNotBlank(requestBody) && !requestBody.equals(pipeline.getApiBody())) {
            logBuffer.append(String.format("[WebAPI] 变量已替换，实际请求体: %s\n", StrUtil.brief(requestBody, 300)));
        }

        // 分页配置存在且启用时走分页循环，否则保持原有单次请求
        PaginationConfig pagination = PaginationConfig.parse(pipeline.getApiPagination());
        if (pagination != null) {
            return extractPaged(requestBody, headers, apiUrl, method, jsonPath, pagination, logBuffer);
        }

        List<String> fieldKeys = parseFieldKeysFromRequestBody(requestBody);
        String body = sendOnce(apiUrl, method, headers, requestBody);
        logBuffer.append(String.format("[WebAPI] 响应成功，数据长度: %d 字符\n", body != null ? body.length() : 0));

        List<Map<String, Object>> records = parseRecords(body, jsonPath, fieldKeys);
        logBuffer.append(String.format("[WebAPI] JsonPath (%s) 抽取解析得到: %d 条记录\n", jsonPath, records.size()));
        return records;
    }

    /**
     * 偏移量分页抽取：每轮改写请求体中的偏移/每页条数，直到本页条数小于结束阈值或达到取数上限
     */
    private List<Map<String, Object>> extractPaged(String requestBody,
                                                   Map<String, String> headers,
                                                   String apiUrl,
                                                   String method,
                                                   String jsonPath,
                                                   PaginationConfig pagination,
                                                   StringBuilder logBuffer) {
        if (!"POST".equals(method)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分页取数目前仅支持 POST 请求体方式");
        }
        if (StrUtil.isBlank(requestBody)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分页取数要求填写请求体 Body");
        }

        JSONObject baseBody;
        try {
            baseBody = JSONUtil.parseObj(requestBody);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分页取数要求请求体为合法 JSON: " + e.getMessage());
        }

        // 金蝶两种报文的分页字段位置不同，按报文体自动切换路径
        pagination.adaptToRequestBody(baseBody);
        logBuffer.append(String.format("[WebAPI-分页] 分页字段路径: 偏移=%s, 每页=%s\n",
                pagination.getOffsetPath(), pagination.getLimitPath()));

        List<String> fieldKeys = parseFieldKeysFromRequestBody(requestBody);
        List<Map<String, Object>> all = new ArrayList<>();

        logBuffer.append(String.format(
                "[WebAPI-分页] 偏移量分页已启用: 每页 %d 条, 最多 %d 次, 起始 %d, 间隔 %d, 结束阈值 < %d, 请求间隔 %d ms\n",
                pagination.getPageSize(), pagination.getMaxPages(), pagination.getStart(),
                pagination.effectiveStep(), pagination.effectiveEnd(), pagination.getIntervalMs()));

        for (int page = 0; page < pagination.getMaxPages(); page++) {
            int offset = pagination.offsetOf(page);
            JSONObject pageBody = JSONUtil.parseObj(baseBody.toString());
            PaginationConfig.setPath(pageBody, pagination.getOffsetPath(), offset);
            PaginationConfig.setPath(pageBody, pagination.getLimitPath(), pagination.getPageSize());

            List<Map<String, Object>> pageRecords;
            try {
                String body = sendOnce(apiUrl, method, headers, pageBody.toString());
                pageRecords = parseRecords(body, jsonPath, fieldKeys);
            } catch (BizException be) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, String.format(
                        "分页第 %d 页拉取失败（此前已成功 %d 页 / %d 条）: %s",
                        page + 1, page, all.size(), be.getMessage()));
            }

            all.addAll(pageRecords);
            logBuffer.append(String.format("[WebAPI-分页] 第 %d 页: 偏移=%d, 本页 %d 条, 累计 %d 条\n",
                    page + 1, offset, pageRecords.size(), all.size()));

            if (pageRecords.size() < pagination.effectiveEnd()) {
                logBuffer.append("[WebAPI-分页] 本页条数小于结束阈值，分页结束\n");
                break;
            }
            if (page + 1 >= pagination.getMaxPages()) {
                logBuffer.append("[WebAPI-分页] 已达最多取数次数上限，停止翻页（可能仍有剩余数据）\n");
                break;
            }
            if (pagination.getIntervalMs() > 0) {
                try {
                    Thread.sleep(pagination.getIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logBuffer.append("[WebAPI-分页] 线程被中断，提前结束分页\n");
                    break;
                }
            }
        }
        logBuffer.append(String.format("[WebAPI-分页] 分页抽取完成，共 %d 条记录\n", all.size()));
        return all;
    }

    /**
     * 逐格检查金蝶业务错误（错误对象可能作为单元格值返回）
     */
    private void assertNoCellBusinessError(List<Map<String, Object>> records) {
        for (Map<String, Object> row : records) {
            if (row.containsKey("Result")) {
                checkErrorNode(row.get("Result"));
            }
            for (Object value : row.values()) {
                if (value instanceof Map<?, ?> cell) {
                    checkErrorNode(cell);
                }
            }
        }
    }

    private void checkErrorNode(Object node) {
        Map<?, ?> statusMap = findResponseStatus(node);
        if (statusMap == null) {
            return;
        }
        if (!Boolean.FALSE.equals(statusMap.get("IsSuccess"))) {
            return;
        }
        String detail = extractErrorMessages(statusMap.get("Errors"));
        throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                "金蝶返回业务错误: " + (StrUtil.isBlank(detail) ? "未知业务错误" : detail));
    }

    /**
     * 找 ResponseStatus 节点：既支持直接挂载，也支持套一层 Result
     */
    private Map<?, ?> findResponseStatus(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = map.get("ResponseStatus");
        if (direct instanceof Map<?, ?> directMap) {
            return directMap;
        }
        Object result = map.get("Result");
        if (result instanceof Map<?, ?> resultMap) {
            Object inner = resultMap.get("ResponseStatus");
            if (inner instanceof Map<?, ?> innerMap) {
                return innerMap;
            }
        }
        return null;
    }

    /**
     * 从 Errors 数组里提取可读消息
     */
    private String extractErrorMessages(Object errors) {
        if (!(errors instanceof List<?> list)) {
            return null;
        }
        StringBuilder message = new StringBuilder();
        for (Object item : list) {
            if (item instanceof Map<?, ?> err) {
                Object msg = err.get("Message");
                if (msg != null && StrUtil.isNotBlank(String.valueOf(msg))) {
                    message.append(message.length() > 0 ? "；" : "").append(msg);
                }
            }
        }
        return message.length() == 0 ? null : message.toString();
    }

    /**
     * 识别金蝶业务层错误。
     *
     * <p>金蝶在参数错误（如字段名不存在）时仍返回 HTTP 200，只是把
     * ResponseStatus.IsSuccess 置为 false。若不拦截，这段错误对象会被当成
     * 一条正常记录载入临时表，导致"静默脏数据"，比直接报错更危险。
     */
    private void assertBusinessSuccess(String body) {
        if (StrUtil.isBlank(body) || !body.trim().startsWith("{")) {
            return;
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONObject status = null;
            Object result = root.get("Result");
            if (result instanceof JSONObject resultObj) {
                Object inner = resultObj.get("ResponseStatus");
                status = inner instanceof JSONObject ? (JSONObject) inner : null;
            }
            if (status == null) {
                Object direct = root.get("ResponseStatus");
                status = direct instanceof JSONObject ? (JSONObject) direct : null;
            }
            if (status == null || !Boolean.FALSE.equals(status.getBool("IsSuccess"))) {
                return;
            }
            String detail = extractErrorMessages(status.get("Errors"));
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                    "金蝶返回业务错误: " + (StrUtil.isBlank(detail) ? "未知业务错误" : detail));
        } catch (BizException be) {
            throw be;
        } catch (Exception ignored) {
            // 非标准应答结构不阻断解析
        }
    }

    /**
     * 发送一次请求并返回响应体
     */
    private String sendOnce(String apiUrl, String method, Map<String, String> headers, String body) {
        HttpRequest request = "POST".equals(method) ? HttpRequest.post(apiUrl) : HttpRequest.get(apiUrl);
        if (!headers.isEmpty()) {
            request.addHeaders(headers);
        }
        if ("POST".equals(method) && StrUtil.isNotBlank(body)) {
            request.body(body);
        }
        try (HttpResponse response = request.timeout(30000).execute()) {
            if (!response.isOk()) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "WebAPI 响应异常，HTTP状态码: " + response.getStatus());
            }
            return response.body();
        } catch (BizException be) {
            throw be;
        } catch (Exception ex) {
            log.error("WebAPI 请求执行失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "WebAPI 抽取失败: " + ex.getMessage());
        }
    }

    /**
     * 按 JsonPath 解析响应，支持对象数组与金蝶二维数组两种形态
     */
    private List<Map<String, Object>> parseRecords(String body, String jsonPath, List<String> fieldKeys) {
        assertBusinessSuccess(body);
        Object extracted = JsonPath.read(body, jsonPath);
        List<Map<String, Object>> records = new ArrayList<>();

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
        // 金蝶 ExecuteBillQuery 出错时返回 [[{"Result":{"ResponseStatus":{...}}}]]，
        // 错误对象藏在数据格子里，根节点检查不到，这里逐格兜底
        assertNoCellBusinessError(records);
        return records;
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
                String acctID = authConfig.getStr("acctID");
                String userName = authConfig.getStr("userName");
                String password = authConfig.getStr("password");

                // 密码留空时沿用已保存的默认凭证，免去每次手填；账套或用户名不匹配则不兜底
                if (StrUtil.isBlank(password)) {
                    String savedPassword = kingdeeCredentialService.resolvePassword(acctID, userName);
                    if (StrUtil.isNotBlank(savedPassword)) {
                        password = savedPassword;
                        if (logBuffer != null) {
                            logBuffer.append("[WebAPI-Auth] 已使用保存的金蝶登录凭证（密码不回显）\n");
                        }
                    }
                }
                if (StrUtil.isBlank(password)) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "登录密码为空，且未找到与之匹配的已保存凭证（账套或用户名不一致）");
                }

                JSONObject kingdeeBody = new JSONObject();
                kingdeeBody.set("acctID", acctID);
                kingdeeBody.set("userName", userName);
                kingdeeBody.set("password", password);
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
            String fieldKeysStr = null;
            // 标准查询：data.FieldKeys
            JSONObject dataObj = obj.getJSONObject("data");
            if (dataObj != null) {
                fieldKeysStr = dataObj.getStr("FieldKeys");
            }
            // 报表方案查询：parameters[0].FieldKeys
            if (StrUtil.isBlank(fieldKeysStr)) {
                cn.hutool.json.JSONArray params = obj.getJSONArray("parameters");
                if (params != null && !params.isEmpty()) {
                    JSONObject first = params.getJSONObject(0);
                    if (first != null) {
                        fieldKeysStr = first.getStr("FieldKeys");
                    }
                }
            }
            // 兜底：根节点 FieldKeys
            if (StrUtil.isBlank(fieldKeysStr)) {
                fieldKeysStr = obj.getStr("FieldKeys");
            }
            if (StrUtil.isNotBlank(fieldKeysStr)) {
                return Arrays.stream(fieldKeysStr.split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .toList();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void validateUrlSafety(String url) {
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
