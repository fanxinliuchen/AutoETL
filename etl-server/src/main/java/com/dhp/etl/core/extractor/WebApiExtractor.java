package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.connector.ApiConnConfig;
import com.dhp.etl.core.connector.ConnectionManager;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import com.dhp.etl.util.ColumnNaming;
import com.dhp.etl.util.FieldMetaResolver;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * WebAPI 数据源抽取组件 (含 JsonPath 解析与 SSRF 防护)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebApiExtractor {

    private final EtlKingdeeCredentialService kingdeeCredentialService;
    private final RequestVarResolver requestVarResolver;
    private final ConnectionManager connectionManager;

    private static final List<String> BLOCKED_IP_PREFIXES = List.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0.", "169.254."
    );

    /** 连接建链超时：金蝶在公网，10 秒足够；失败要尽快暴露，不占读超时预算 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** 读超时：深偏移分页（20 万条以后）实测单页 18~23 秒，给到 120 秒留足余量 */
    private static final int READ_TIMEOUT_MS = 120_000;

    /** 单页/单次请求遇到网络类错误时的重试次数（不含首次） */
    private static final int MAX_NETWORK_RETRIES = 3;

    /** 重试退避基数：2s、4s、8s */
    private static final long RETRY_BACKOFF_MS = 2_000L;

    /** 会话失效类错误关键字：命中则重新登录并重试当前页（金蝶/云表/通用 WebAPI 的常见文案） */
    private static final List<String> SESSION_EXPIRED_HINTS = List.of(
            "会话信息已丢失", "请重新登录", "重新登入", "会话已过期", "会话超时",
            "登录已过期", "token 过期", "token已过期");

    /**
     * 抽取 WebAPI 结果数据（全量返回）。
     *
     * <p>注意：大数据量请用 {@link #extractStreaming}，边抽边消费，避免整表驻留内存。
     */
    public List<Map<String, Object>> extract(EtlPipeline pipeline, StringBuilder logBuffer) {
        return extract(pipeline, logBuffer, Collections.emptyMap());
    }

    /**
     * @param loopVars 循环取数当前轮的参数（列名 -> 值），优先级高于变量定义
     */
    public List<Map<String, Object>> extract(EtlPipeline pipeline,
                                             StringBuilder logBuffer,
                                             Map<String, String> loopVars) {
        List<Map<String, Object>> collected = new ArrayList<>();
        doExtract(pipeline, logBuffer, loopVars, (rows, buf) -> collected.addAll(rows));
        return collected;
    }

    /**
     * 流式抽取：每解析完一页就交给消费方（通常是「写临时表」），
     * 内存占用从「整表」降到「一页」。
     *
     * <p>消费方抛异常会终止抽取并向上冒泡；已消费的页不回滚（临时表里保留，
     * 下次运行开始时会 TRUNCATE）。
     *
     * @return 抽取总行数
     */
    public long extractStreaming(EtlPipeline pipeline,
                                 StringBuilder logBuffer,
                                 Map<String, String> loopVars,
                                 BiConsumer<List<Map<String, Object>>, StringBuilder> rowConsumer) {
        return doExtract(pipeline, logBuffer, loopVars, rowConsumer);
    }

    /**
     * 抽取主流程：解析配置 → 鉴权 → 单次或分页拉取，每页结果交给 {@code rowConsumer}。
     */
    private long doExtract(EtlPipeline pipeline,
                           StringBuilder logBuffer,
                           Map<String, String> loopVars,
                           BiConsumer<List<Map<String, Object>>, StringBuilder> rowConsumer) {
        // 云表：报文体是 formData + queryParams（字符串化 JSON），走独立分支
        ConnTypeEnum connType = sourceConnType(pipeline);
        if (connType == ConnTypeEnum.IYUNBIAO) {
            List<Map<String, Object>> records = extractYunbiao(pipeline, logBuffer, loopVars);
            if (!records.isEmpty()) {
                rowConsumer.accept(records, logBuffer);
            }
            return records.size();
        }

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

        // ===== 步骤一: 鉴权。优先用源连接（连接注册中心）；老流程回落到流程内 api_auth_config =====
        applyAuth(pipeline, headers, logBuffer);

        logBuffer.append(String.format("[WebAPI] 数据拉取地址: %s, 方式: %s\n", apiUrl, method));

        // 变量在后端解析：内置 ${today}/${yesterday}/${now}，以及过滤条件里定义的 SQL 变量
        String requestBody = requestVarResolver.resolve(pipeline, pipeline.getApiBody(), logBuffer, loopVars);
        if (StrUtil.isNotBlank(requestBody) && !requestBody.equals(pipeline.getApiBody())) {
            logBuffer.append(String.format("[WebAPI] 变量已替换，实际请求体: %s\n", StrUtil.brief(requestBody, 300)));
        }

        // 分页配置存在且启用时走分页循环，否则保持原有单次请求
        PaginationConfig pagination = PaginationConfig.parse(pipeline.getApiPagination());
        List<String> fieldKeys = parseFieldKeysFromRequestBody(requestBody);
        if (pagination != null) {
            // 会话失效自动重登：金蝶 Cookie 有固定有效期（实测约 30 分钟），
            // 大表翻页跑到一半会失效；重登后从当前页续跑，避免整批失败
            Runnable reAuth = () -> {
                headers.remove("Cookie");
                applyAuth(pipeline, headers, logBuffer);
            };
            return extractPaged(requestBody, headers, apiUrl, method, jsonPath, pagination, logBuffer,
                    fieldKeys, pipeline.getFieldMeta(), false, reAuth, rowConsumer);
        }

        String body = sendWithRetry("单次请求", apiUrl, method, headers, requestBody, false, logBuffer);
        logBuffer.append(String.format("[WebAPI] 响应成功，数据长度: %d 字符\n", body != null ? body.length() : 0));

        List<Map<String, Object>> records = parseRecords(body, jsonPath, fieldKeys, pipeline.getFieldMeta(), logBuffer);
        logBuffer.append(String.format("[WebAPI] JsonPath (%s) 抽取解析得到: %d 条记录\n", jsonPath, records.size()));
        if (!records.isEmpty()) {
            rowConsumer.accept(records, logBuffer);
        }
        return records.size();
    }

    /**
     * 云表抽取：formData + queryParams（字符串化 JSON）+ 签名头。
     *
     * <p>报文在流程里存成 JSON 对象（`queryParams` 是对象），变量替换与分页改写都作用在这个对象上，
     * **发送前**才把 queryParams 序列化成压缩 JSON 作为表单字段——顺序不能反过来，
     * 否则分页引擎按 JSON 路径改写时会改到字符串内部而失效。
     */
    private List<Map<String, Object>> extractYunbiao(EtlPipeline pipeline,
                                                     StringBuilder logBuffer,
                                                     Map<String, String> loopVars) {
        EtlDatasource conn = connectionManager.load(pipeline.getSourceDsId());
        ApiConnConfig cfg = connectionManager.apiConfig(conn);
        String apiUrl = resolveYunbiaoUrl(cfg, pipeline.getApiUrl());
        validateUrlSafety(apiUrl);

        try {
            return doExtractYunbiao(pipeline, apiUrl, logBuffer, loopVars);
        } catch (BizException first) {
            // 云表同一账号只保留一个会话：在别处（浏览器 / 调试脚本）登录会把缓存里的 token 顶掉，
            // 表现为「连接超时，或其他人登入,请重新登入!」。清缓存重登一次再试，
            // 否则定时任务会因为会话漂移整批失败。
            connectionManager.invalidate(pipeline.getSourceDsId());
            logBuffer.append("[云表] 首次请求失败（")
                    .append(StrUtil.brief(StrUtil.nullToEmpty(first.getMessage()), 120))
                    .append("），已清除会话缓存并重登后重试\n");
            return doExtractYunbiao(pipeline, apiUrl, logBuffer, loopVars);
        }
    }

    private List<Map<String, Object>> doExtractYunbiao(EtlPipeline pipeline,
                                                        String apiUrl,
                                                        StringBuilder logBuffer,
                                                        Map<String, String> loopVars) {
        Map<String, String> headers = new HashMap<>(connectionManager.authHeaders(pipeline.getSourceDsId(), logBuffer));
        logBuffer.append(String.format("[云表] 数据拉取地址: %s, 方式: POST（formData）\n", apiUrl));

        String requestBody = requestVarResolver.resolve(pipeline, pipeline.getApiBody(), logBuffer, loopVars);
        if (StrUtil.isBlank(requestBody)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "云表取数需要填写请求体（formJson）");
        }
        String jsonPath = StrUtil.blankToDefault(pipeline.getApiDataPath(), "$");
        List<String> fieldKeys = resolveFieldKeys(pipeline, requestBody);

        PaginationConfig pagination = PaginationConfig.parse(pipeline.getApiPagination());
        if (pagination != null) {
            // 云表 token 15 分钟过期，长分页同样会在中途失效：清会话缓存后重登，续跑当前页
            Runnable reAuth = () -> {
                connectionManager.invalidate(pipeline.getSourceDsId());
                headers.clear();
                headers.putAll(connectionManager.authHeaders(pipeline.getSourceDsId(), logBuffer));
            };
            List<Map<String, Object>> all = new ArrayList<>();
            extractPaged(requestBody, headers, apiUrl, "POST", jsonPath, pagination, logBuffer,
                    fieldKeys, pipeline.getFieldMeta(), true, reAuth,
                    (rows, buf) -> all.addAll(rows));
            return all;
        }
        String body = sendWithRetry("单次请求", apiUrl, "POST", headers, requestBody, true, logBuffer);
        logBuffer.append(String.format("[云表] 响应成功，数据长度: %d 字符\n", body != null ? body.length() : 0));
        List<Map<String, Object>> records = parseRecords(body, jsonPath, fieldKeys, pipeline.getFieldMeta(), logBuffer);
        logBuffer.append(String.format("[云表] JsonPath (%s) 抽取解析得到: %d 条记录\n", jsonPath, records.size()));
        return records;
    }

    /**
     * 云表 URL：连接里存服务地址（含 enpId），流程里存**表单名称**。
     *
     * <p>实测云表查询接口是 {@code POST /{enpId}/openapi/1.0/{表单名}}（不带 /query），
     * 分页参数放在表单字段 {@code formJson} 里。所以这里只让用户填表单名，
     * {@code openapi/1.0/} 前缀由后端补全：
     * <pre>
     *   表结合数采明细_IOT                    → {base}/openapi/1.0/表结合数采明细_IOT
     *   openapi/1.0/表结合数采明细_IOT        → 原样（兼容旧配置）
     *   http://host/10001/openapi/1.0/xxx     → 原样（兼容手填完整 URL）
     * </pre>
     */
    private String resolveYunbiaoUrl(ApiConnConfig cfg, String apiUrl) {
        return buildYunbiaoUrl(cfg.yunbiaoBaseUrl(), apiUrl);
    }

    /** 便于单测：表单名 / 相对路径 / 完整 URL 三种写法的拼装规则 */
    static String buildYunbiaoUrl(String baseUrl, String apiUrl) {
        if (StrUtil.isBlank(apiUrl)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "云表取数需要填写表单名称");
        }
        String trimmed = apiUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        String relative = StrUtil.removePrefix(trimmed, "/");
        if (!relative.startsWith("openapi/")) {
            relative = "openapi/1.0/" + relative;
        }
        return StrUtil.removeSuffix(baseUrl, "/") + "/" + relative;
    }

    /** 字段清单：报文里没有 FieldKeys 时（自定义报文/云表）用流程配置的字段 */
    private List<String> resolveFieldKeys(EtlPipeline pipeline, String requestBody) {
        List<String> fromBody = parseFieldKeysFromRequestBody(requestBody);
        if (fromBody != null && !fromBody.isEmpty()) {
            return fromBody;
        }
        List<String> fromMeta = FieldMetaResolver.enabledKeys(pipeline.getFieldMeta());
        return fromMeta.isEmpty() ? null : fromMeta;
    }

    /**
     * 源连接类型：只有 API 类连接（金蝶/云表）会返回，MySQL 源或无连接返回 null
     */
    private ConnTypeEnum sourceConnType(EtlPipeline pipeline) {
        if (pipeline.getSourceDsId() == null) {
            return null;
        }
        ConnTypeEnum type = connectionManager.typeOf(connectionManager.load(pipeline.getSourceDsId()));
        return type.isApi() ? type : null;
    }

    /**
     * 鉴权取值顺序：源连接（新）→ 流程内 api_auth_config（旧）
     */
    private void applyAuth(EtlPipeline pipeline, Map<String, String> headers, StringBuilder logBuffer) {
        if (sourceConnType(pipeline) != null) {
            headers.putAll(connectionManager.authHeaders(pipeline.getSourceDsId(), logBuffer));
            return;
        }
        executePreAuthIfConfigured(pipeline, headers, logBuffer);
    }

    /**
     * 偏移量分页抽取：每轮改写请求体中的偏移/每页条数，直到本页条数小于结束阈值或达到取数上限
     */
    private long extractPaged(String requestBody,
                              Map<String, String> headers,
                              String apiUrl,
                              String method,
                              String jsonPath,
                              PaginationConfig pagination,
                              StringBuilder logBuffer,
                              List<String> fieldKeys,
                              String fieldMeta,
                              boolean formBody,
                              Runnable reAuth,
                              BiConsumer<List<Map<String, Object>>, StringBuilder> rowConsumer) {
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

        List<String> pagedFieldKeys = fieldKeys;
        long totalRecords = 0L;

        logBuffer.append(String.format(
                "[WebAPI-分页] 偏移量分页已启用: 每页 %d 条, 最多 %d 次, 起始 %d, 间隔 %d, 结束阈值 < %d, 请求间隔 %d ms\n",
                pagination.getPageSize(), pagination.getMaxPages(), pagination.getStart(),
                pagination.effectiveStep(), pagination.effectiveEnd(), pagination.getIntervalMs()));

        for (int page = 0; page < pagination.getMaxPages(); page++) {
            int offset = pagination.offsetOf(page);
            JSONObject pageBody = JSONUtil.parseObj(baseBody.toString());
            PaginationConfig.setPath(pageBody, pagination.getOffsetPath(), offset);
            PaginationConfig.setPath(pageBody, pagination.getLimitPath(), pagination.getPageSize());

            List<Map<String, Object>> pageRecords = null;
            boolean reAuthed = false;
            while (true) {
                try {
                    // 网络超时 / 连接重置 / HTTP 5xx 在这里按页重试（指数退避），不整批重来
                    String body = sendWithRetry("第 " + (page + 1) + " 页",
                            apiUrl, method, headers, pageBody.toString(), formBody, logBuffer);
                    pageRecords = parseRecords(body, jsonPath, pagedFieldKeys, fieldMeta, logBuffer);
                    break;
                } catch (BizException be) {
                    // 会话有固定有效期（金蝶实测约 30 分钟）：大表翻页中途失效时重新登录，
                    // 从当前页续跑，别让几十分钟的抽取整批作废
                    boolean canRetry = !reAuthed && reAuth != null && isSessionExpired(be.getMessage());
                    if (canRetry) {
                        reAuthed = true;
                        logBuffer.append(String.format(
                                "[WebAPI-分页] 第 %d 页会话已失效（%s），重新登录后重试该页\n",
                                page + 1, StrUtil.brief(StrUtil.nullToEmpty(be.getMessage()), 100)));
                        reAuth.run();
                        continue;
                    }
                    throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, String.format(
                            "分页第 %d 页拉取失败（此前已成功 %d 页 / %d 条）: %s",
                            page + 1, page, totalRecords, be.getMessage()));
                }
            }

            // 边抽边消费：写临时表，页级数据用完即释放，内存不再随总行数增长
            rowConsumer.accept(pageRecords, logBuffer);
            totalRecords += pageRecords.size();
            logBuffer.append(String.format("[WebAPI-分页] 第 %d 页: 偏移=%d, 本页 %d 条, 累计 %d 条\n",
                    page + 1, offset, pageRecords.size(), totalRecords));

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
        logBuffer.append(String.format("[WebAPI-分页] 分页抽取完成，共 %d 条记录\n", totalRecords));
        return totalRecords;
    }

    /**
     * 判断异常文案是否属于「会话失效」。
     *
     * <p>只认明确要求重新登录的文案，避免把业务错误（字段不存在、参数非法）误判成会话问题反复重试。
     */
    static boolean isSessionExpired(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        for (String hint : SESSION_EXPIRED_HINTS) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        return sendOnce(apiUrl, method, headers, body, false);
    }

    /**
     * @param formBody true 表示按 formData 提交（云表：只有一个 queryParams 字段，值是压缩 JSON 字符串）
     */
    private String sendOnce(String apiUrl, String method, Map<String, String> headers, String body, boolean formBody) {
        HttpRequest request = "POST".equals(method) ? HttpRequest.post(apiUrl) : HttpRequest.get(apiUrl);
        if (!headers.isEmpty()) {
            request.addHeaders(headers);
        }
        if ("POST".equals(method) && StrUtil.isNotBlank(body)) {
            if (formBody) {
                // 云表所有 POST 都是 formData：报文顶层每个字段就是一个表单字段，
                // 值为对象/数组时压成一行 JSON（云表的 formJson 就是这个形状）
                request.header("Content-Type", "application/x-www-form-urlencoded");
                parseFormFields(body).forEach(request::form);
            } else {
                request.body(body);
            }
        }
        try (HttpResponse response = request
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            if (!response.isOk()) {
                int status = response.getStatus();
                String msg = "WebAPI 响应异常，HTTP状态码: " + status;
                if (isRetryableHttpStatus(status)) {
                    throw new RetryableApiException(ErrorCode.ETL_EXECUTE_ERROR, msg);
                }
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, msg);
            }
            return response.body();
        } catch (BizException be) {
            throw be;
        } catch (Exception ex) {
            log.error("WebAPI 请求执行失败: {}", ex.getMessage());
            String msg = "WebAPI 抽取失败: " + ex.getMessage();
            if (isRetryableNetworkError(ex)) {
                throw new RetryableApiException(ErrorCode.ETL_EXECUTE_ERROR, msg);
            }
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, msg);
        }
    }

    /**
     * 带重试的单次调用：网络类异常按 2s / 4s / 8s 退避重试同一请求。
     *
     * <p>抽取接口都是只读查询（ExecuteBillQuery / 云表 query），重试不会产生副作用。
     * HTTP 4xx、业务错误不重试，避免把参数问题放大成多次无效请求。
     */
    private String sendWithRetry(String label,
                                 String apiUrl,
                                 String method,
                                 Map<String, String> headers,
                                 String body,
                                 boolean formBody,
                                 StringBuilder logBuffer) {
        int attempt = 0;
        while (true) {
            try {
                return sendOnce(apiUrl, method, headers, body, formBody);
            } catch (RetryableApiException ex) {
                if (attempt >= MAX_NETWORK_RETRIES) {
                    throw ex;
                }
                long waitMs = RETRY_BACKOFF_MS << attempt;
                attempt++;
                if (logBuffer != null) {
                    logBuffer.append(String.format(
                            "[WebAPI-重试] %s 请求异常（%s），%d 秒后第 %d/%d 次重试\n",
                            label, StrUtil.brief(StrUtil.nullToEmpty(ex.getMessage()), 120),
                            waitMs / 1000, attempt, MAX_NETWORK_RETRIES));
                }
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    /** HTTP 5xx / 429 属于服务端临时不可用，可重试；4xx 是请求本身有问题，不重试 */
    static boolean isRetryableHttpStatus(int status) {
        return status >= 500 || status == 429;
    }

    /**
     * 网络类异常判定：Hutool 会把 IOException 包成 IORuntimeException，
     * 所以这里顺着 cause 链找 SocketTimeout / Connect / Socket / 其它 IO 异常。
     */
    static boolean isRetryableNetworkError(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = ex; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SocketTimeoutException
                    || t instanceof ConnectException
                    || t instanceof SocketException
                    || t instanceof UnknownHostException) {
                return true;
            }
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 云表 formData 报文：顶层每个字段即一个表单字段。
     *
     * <p>云表的查询接口要的是 {@code formJson}，值就是分页对象本身：
     * <pre>
     *   报文 {"formJson": {"pageInfo": {...}, "filter": [], "paramList": []}}
     *   → formData  formJson={"pageInfo":{...},"filter":[],"paramList":[]}
     * </pre>
     * 值为字符串时原样发送（兼容用户直接粘贴压缩 JSON 串的情况）。
     */
    static Map<String, String> parseFormFields(String body) {
        JSONObject root;
        try {
            root = JSONUtil.parseObj(body);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "云表请求体必须是合法 JSON: " + e.getMessage());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : root.keySet()) {
            Object value = root.get(key);
            if (value == null) {
                continue;
            }
            fields.put(key, value instanceof CharSequence text
                    ? text.toString()
                    : JSONUtil.toJsonStr(value));
        }
        if (fields.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "云表请求体为空，需至少一个表单字段（查询接口为 formJson）");
        }
        return fields;
    }

    /**
     * 按 JsonPath 解析响应，支持对象数组与金蝶二维数组两种形态
     */
    private List<Map<String, Object>> parseRecords(String body, String jsonPath, List<String> fieldKeys,
                                                   String fieldMeta, StringBuilder logBuffer) {
        assertBusinessSuccess(body);
        Object extracted = JsonPath.read(body, jsonPath);
        List<Map<String, Object>> records = new ArrayList<>();
        // 字段标识可能带点（FSaleOrgId.FNumber）或为中文（云表数据项），落库列名统一在这里换算；
        // 字段清单里手工指定的「列名」优先，否则按标识换算
        Map<String, String> columnByKey = FieldMetaResolver.columnMapping(fieldMeta, fieldKeys);

        if (extracted instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    records.add(projectRecord(map, fieldKeys, columnByKey));
                } else if (item instanceof List<?> rowList) {
                    // 金蝶 ExecuteBillQuery 常返回二维数组: [ [1, "A"], [2, "B"] ]
                    // 但 JsonPath 写成 $.* 时，整个数据数组会被当成"一行"传进来（元素是对象），
                    // 那样会把 N 行数据当成 N 列，这里识别出来逐个展开
                    if (!rowList.isEmpty() && rowList.get(0) instanceof Map<?, ?> firstRow) {
                        for (Object rowItem : rowList) {
                            if (rowItem instanceof Map<?, ?> rowMap) {
                                records.add(projectRecord(rowMap, fieldKeys, columnByKey));
                            }
                        }
                    } else {
                        Map<String, Object> record = new LinkedHashMap<>();
                        for (int i = 0; i < rowList.size(); i++) {
                            String colName = "col_" + i;
                            if (fieldKeys != null && i < fieldKeys.size()) {
                                String rawKey = fieldKeys.get(i);
                                colName = columnByKey.getOrDefault(rawKey, ColumnNaming.toColumn(rawKey));
                            }
                            record.put(colName, rowList.get(i));
                        }
                        records.add(record);
                    }
                }
            }
        } else if (extracted instanceof Map<?, ?> map) {
            records.add(projectRecord(map, fieldKeys, columnByKey));
        }
        // 金蝶 ExecuteBillQuery 出错时返回 [[{"Result":{"ResponseStatus":{...}}}]]，
        // 错误对象藏在数据格子里，根节点检查不到，这里逐格兜底
        assertNoCellBusinessError(records);
        return records;
    }

    /**
     * 按配置的 FieldKeys 裁剪记录，保持配置顺序。
     *
     * <p>金蝶报表接口就算传了 FieldKeys，也可能把过滤方案的全部列都返回（实测 868 列），
     * 不裁剪会按 800+ 列去建临时表，直接撞 InnoDB 行大小上限。
     * FieldKeys 为空或一个都对不上时保留原样，避免误裁。
     */
    private Map<String, Object> projectRecord(Map<?, ?> map, List<String> fieldKeys,
                                             Map<String, String> columnByKey) {
        Map<String, Object> record = new LinkedHashMap<>();
        if (fieldKeys != null && !fieldKeys.isEmpty()) {
            for (String key : fieldKeys) {
                if (map.containsKey(key)) {
                    record.put(columnByKey.getOrDefault(key, ColumnNaming.toColumn(key)), map.get(key));
                }
            }
            log.debug("[WebAPI] 记录裁剪: 原始列={}, 配置字段={}, 命中={}",
                    map.size(), fieldKeys.size(), record.size());
            if (!record.isEmpty()) {
                return record;
            }
        }
        // 配置的字段一个都没对上：按响应原样落库，同样换算成合法列名
        Map<String, String> fallback = ColumnNaming.mapping(
                map.keySet().stream().map(String::valueOf).toList());
        map.forEach((k, v) -> {
            String raw = String.valueOf(k);
            record.put(fallback.getOrDefault(raw, ColumnNaming.toColumn(raw)), v);
        });
        return record;
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

            try (HttpResponse authResp = authReq
                    .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(30_000)
                    .execute()) {
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
