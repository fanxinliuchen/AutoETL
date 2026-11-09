package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.dto.KingdeeFormFieldQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 金蝶云星空表单元数据客户端。
 *
 * <p>登录（复用 KINGDEE_K3 鉴权流程）后调用 DynamicFormService.QueryBusinessInfo，
 * 拉取指定表单的全部字段定义，自动带出字段标识、中文名与数据类型，
 * 免去手工拼写 FieldKeys。
 *
 * <p>注意：金蝶各版本 QueryBusinessInfo 的返回结构存在差异，因此解析采用
 * 「递归查找形似字段定义的节点」的容错策略，而不是绑定某个固定层级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KingdeeMetadataClient {

    private static final String QUERY_BUSINESS_INFO_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.QueryBusinessInfo.common.kdsvc";

    /** 字段标识候选键，按优先级排列 */
    private static final String[] KEY_FIELDS = {"Key", "FieldKey", "FieldId", "FieldName"};

    /** 中文名候选键 */
    private static final String[] LABEL_FIELDS = {"Caption", "DisplayName", "Name", "Title", "FieldName"};

    /** 类型候选键 */
    private static final String[] TYPE_FIELDS = {"FieldType", "DataType", "ValueType", "Type"};

    private final WebApiExtractor webApiExtractor;

    /**
     * 查询表单字段元数据
     *
     * @return 每项包含 key / label / type / rawType
     */
    public List<Map<String, Object>> queryFormFields(KingdeeFormFieldQueryDTO dto) {
        String cookie = login(dto);
        String metadataUrl = buildMetadataUrl(dto.getLoginUrl());
        webApiExtractor.validateUrlSafety(metadataUrl);

        JSONObject payload = new JSONObject();
        payload.set("data", new JSONObject().set("FormId", dto.getFormId()));

        String responseBody;
        try (HttpResponse response = HttpRequest.post(metadataUrl)
                .header("Content-Type", "application/json")
                .header("Cookie", StrUtil.nullToEmpty(cookie))
                .body(payload.toString())
                .timeout(30000)
                .execute()) {
            if (!response.isOk()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "查询表单字段失败，HTTP 状态码: " + response.getStatus());
            }
            responseBody = response.body();
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "调用金蝶元数据接口异常: " + e.getMessage());
        }

        return parseFields(responseBody, dto.getFormId());
    }

    /**
     * 登录并返回会话 Cookie（复用既有的 KINGDEE_K3 前置鉴权逻辑）
     */
    private String login(KingdeeFormFieldQueryDTO dto) {
        JSONObject authConfig = new JSONObject()
                .set("loginUrl", dto.getLoginUrl())
                .set("acctID", StrUtil.nullToEmpty(dto.getAcctID()))
                .set("userName", StrUtil.nullToEmpty(dto.getUserName()))
                .set("password", StrUtil.nullToEmpty(dto.getPassword()))
                .set("lcid", dto.getLcid() == null ? 2052 : dto.getLcid());

        Map<String, Object> authResult = webApiExtractor.executePreAuth("KINGDEE_K3", authConfig.toString(), null);
        if (!Boolean.TRUE.equals(authResult.get("success"))) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶登录失败: " + StrUtil.blankToDefault(String.valueOf(authResult.get("message")), "未知原因"));
        }
        return String.valueOf(authResult.getOrDefault("cookie", ""));
    }

    private String buildMetadataUrl(String loginUrl) {
        try {
            URI uri = new URI(loginUrl);
            if (StrUtil.isBlank(uri.getScheme()) || StrUtil.isBlank(uri.getAuthority())) {
                throw new IllegalArgumentException("登录地址缺少协议或主机");
            }
            return uri.getScheme() + "://" + uri.getAuthority() + QUERY_BUSINESS_INFO_PATH;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无法由登录地址推导元数据接口: " + e.getMessage());
        }
    }

    /**
     * 解析 QueryBusinessInfo 返回。容错处理不同版本的返回结构差异。
     */
    List<Map<String, Object>> parseFields(String responseBody, String formId) {
        if (StrUtil.isBlank(responseBody)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金蝶元数据接口返回为空");
        }

        // 网关/代理返回 HTML 错误页时先拦掉，否则 Hutool 会宽容地解析成空对象，报错信息会误导排查
        String trimmed = responseBody.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶元数据返回不是合法 JSON（可能被网关或代理拦截），片段: " + StrUtil.brief(responseBody, 120));
        }

        JSONObject root;
        try {
            root = JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金蝶元数据返回不是合法 JSON: " + StrUtil.brief(responseBody, 120));
        }

        assertSuccess(root, formId);

        List<JSONObject> candidates = new ArrayList<>();
        collectFieldCandidates(root, candidates);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (JSONObject candidate : candidates) {
            String key = firstNonBlank(candidate, KEY_FIELDS);
            if (StrUtil.isBlank(key)) {
                continue;
            }
            // 字段标识里出现空格/中文的，基本不是真实字段键，跳过
            if (!key.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                continue;
            }
            String label = firstNonBlank(candidate, LABEL_FIELDS);
            String rawType = firstNonBlank(candidate, TYPE_FIELDS);
            String type = mapKingdeeType(rawType, key);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("label", StrUtil.blankToDefault(label, key));
            item.put("type", type);
            item.put("rawType", StrUtil.nullToEmpty(rawType));
            merged.putIfAbsent(key.toUpperCase(), item);
        }

        if (merged.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未能从返回结果中识别出字段定义，原始返回片段: " + StrUtil.brief(responseBody, 200));
        }
        return new ArrayList<>(merged.values());
    }

    private void assertSuccess(JSONObject root, String formId) {
        JSONObject result = safeObj(root, "Result");
        if (result == null) {
            return;
        }
        JSONObject status = safeObj(result, "ResponseStatus");
        if (status == null) {
            return;
        }
        Boolean isSuccess = status.getBool("IsSuccess");
        if (Boolean.FALSE.equals(isSuccess)) {
            String message = "未知错误";
            JSONArray errors = status.getJSONArray("Errors");
            if (errors != null && !errors.isEmpty()) {
                Object first = errors.get(0);
                if (first instanceof JSONObject) {
                    String msg = ((JSONObject) first).getStr("Message");
                    if (StrUtil.isNotBlank(msg)) {
                        message = msg;
                    }
                } else if (first != null) {
                    message = String.valueOf(first);
                }
            }
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "查询表单 [" + formId + "] 字段失败: " + message);
        }
    }

    private JSONObject safeObj(JSONObject parent, String field) {
        Object value = parent.get(field);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    /**
     * 递归查找形似字段定义的节点（同时具备标识键与名称/类型键）
     */
    private void collectFieldCandidates(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject obj) {
            if (looksLikeField(obj)) {
                out.add(obj);
            }
            for (Object value : obj.values()) {
                collectFieldCandidates(value, out);
            }
        } else if (node instanceof JSONArray array) {
            for (Object item : array) {
                collectFieldCandidates(item, out);
            }
        }
    }

    private boolean looksLikeField(JSONObject obj) {
        String key = firstNonBlank(obj, KEY_FIELDS);
        if (StrUtil.isBlank(key)) {
            return false;
        }
        return StrUtil.isNotBlank(firstNonBlank(obj, LABEL_FIELDS))
                || StrUtil.isNotBlank(firstNonBlank(obj, TYPE_FIELDS));
    }

    private String firstNonBlank(JSONObject obj, String[] fields) {
        for (String field : fields) {
            Object value = obj.get(field);
            if (value instanceof String s && StrUtil.isNotBlank(s)) {
                return s.trim();
            }
        }
        return null;
    }

    /**
     * 金蝶字段类型 -> 本项目临时表类型白名单
     */
    String mapKingdeeType(String rawType, String fieldKey) {
        String type = StrUtil.nullToEmpty(rawType).toUpperCase();
        if (StrUtil.isNotBlank(type)) {
            if (type.contains("DATETIME") || (type.contains("TIME") && !type.contains("DATE"))) {
                return "DATETIME";
            }
            if (type.contains("DATE")) {
                return "DATE";
            }
            if (type.contains("DECIMAL") || type.contains("NUMERIC") || type.contains("MONEY")
                    || type.contains("AMOUNT") || type.contains("PRICE") || type.contains("QTY")
                    || type.contains("QUANTITY") || type.contains("RATE")) {
                return "DECIMAL(18,4)";
            }
            if (type.contains("BOOL")) {
                return "VARCHAR(16)";
            }
            if (type.contains("INT") || type.contains("LONG")) {
                return "BIGINT";
            }
            if (type.contains("TEXT") || type.contains("STRING") || type.contains("CHAR")) {
                return "VARCHAR(255)";
            }
        }
        return inferTypeByKey(fieldKey);
    }

    /**
     * 按字段名推断类型（元数据缺失时的兜底，同时供前端「推断类型」保持一致口径）
     */
    public static String inferTypeByKey(String fieldKey) {
        String key = StrUtil.nullToEmpty(fieldKey).toUpperCase();
        if (StrUtil.isBlank(key)) {
            return "TEXT";
        }
        String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        if (leaf.endsWith("ID") || leaf.equals("FID")) {
            return "BIGINT";
        }
        if (leaf.contains("DATETIME") || (leaf.contains("TIME") && !leaf.contains("DATE"))) {
            return "DATETIME";
        }
        if (leaf.contains("DATE")) {
            return "DATE";
        }
        if (leaf.contains("AMOUNT") || leaf.contains("PRICE") || leaf.contains("QTY")
                || leaf.contains("RATE") || leaf.contains("TAX") || leaf.contains("COST")
                || leaf.contains("WEIGHT") || leaf.contains("VOLUME") || leaf.contains("BALANCE")) {
            return "DECIMAL(18,4)";
        }
        if (leaf.contains("COUNT") || leaf.contains("SEQ")) {
            return "INT";
        }
        if (leaf.contains("STATUS") || leaf.contains("FLAG") || leaf.contains("ENABLE")) {
            return "VARCHAR(32)";
        }
        return "VARCHAR(255)";
    }
}
