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
import java.util.List;
import java.util.Map;

/**
 * 金蝶云星空表单元数据客户端。
 *
 * <p>登录（复用 KINGDEE_K3 鉴权流程）后调用 DynamicFormService.QueryBusinessInfo，
 * 拉取指定表单的全部字段定义，自动带出字段标识、中文名与数据类型。
 *
 * <p>真实返回结构（已实测 PUR_PurchaseOrder）：
 * <pre>
 * Result.NeedReturnData.Entrys[].Fields[] = {
 *   "Key": "FBillNo",                 // 查询时使用的字段键
 *   "Name": [{"Key":2052,"Value":"单据编号"}],  // 多语言名称，Value 才是中文名
 *   "FieldName": "FBILLNO",           // 数据库物理列名（大写）
 *   "FieldType": 231,                 // 数字枚举，非字符串
 *   "Extends": [...],                 // 有值表示枚举字段
 *   "LookUpObjectFormId": "BD_Supplier", // 有值表示基础资料引用
 *   "Editlen": 50                     // 文本长度
 * }
 * </pre>
 *
 * <p>注意：Entry（单据体）节点也带 Key/Name，必须排除，否则会把 FBillHead 当成字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KingdeeMetadataClient {

    private static final String QUERY_BUSINESS_INFO_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.QueryBusinessInfo.common.kdsvc";

    /** 字段标识候选键，按优先级排列（Key 是查询键，FieldName 是物理列名，故 Key 优先） */
    private static final String[] KEY_FIELDS = {"Key", "FieldKey", "FieldId"};

    private final WebApiExtractor webApiExtractor;

    /**
     * 查询表单字段元数据
     *
     * @return 每项包含 key / label / type / rawType
     */
    public List<Map<String, Object>> queryFormFields(KingdeeFormFieldQueryDTO dto) {
        return parseFields(fetchBusinessInfo(dto), dto.getFormId());
    }

    /**
     * 识别表单属于「单据」还是「报表」，供前端「取数方式 = 自动识别」使用。
     *
     * <p>判据不是 FormId 的命名（STK_InStock 是单据、STK_StockSummaryRpt 是报表，前缀靠不住），
     * 而是 QueryBusinessInfo 能否吐出字段定义：
     * 有字段 -> 单据（data.FormId 报文）；无字段 -> 报表（parameters + FSCHEMEID 报文）。
     */
    public Map<String, Object> detectFormType(KingdeeFormFieldQueryDTO dto) {
        return detectTypeFromBody(fetchBusinessInfo(dto), dto.getFormId());
    }

    /**
     * 纯解析层：由 QueryBusinessInfo 的原始返回判断表单类型（便于单测，不发网络请求）
     */
    Map<String, Object> detectTypeFromBody(String responseBody, String formId) {
        JSONObject root = parseRoot(responseBody);
        assertSuccess(root, formId);
        List<Map<String, Object>> fields = extractFields(root);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formId", formId);
        result.put("fieldCount", fields.size());
        if (!fields.isEmpty()) {
            result.put("type", "BILL");
            result.put("reason", "金蝶返回了 " + fields.size() + " 个字段定义，按标准单据查询（data.FormId）取数");
        } else {
            result.put("type", "REPORT");
            result.put("reason", reportReason(root));
        }
        return result;
    }

    /**
     * 无字段定义时的判定说明，把金蝶返回的报表特征一并带上，便于排查。
     *
     * <p>不同版本把 LinkSet 挂在 Result 或 NeedReturnData 下，这里递归查找，避免判据落空。
     */
    private String reportReason(JSONObject root) {
        StringBuilder reason = new StringBuilder(
                "金蝶未返回单据字段定义，判定为报表/账表类表单，按报表方案查询（parameters + FSCHEMEID）取数");
        Object isNeedLinkTable = findDeep(root, "IsNeedLinkTable");
        Object linkEntitys = findDeep(root, "LinkEntitys");
        if (isNeedLinkTable != null || linkEntitys != null) {
            reason.append("；返回特征：IsNeedLinkTable=").append(isNeedLinkTable)
                    .append(", LinkEntitys=").append(describeLinkEntitys(linkEntitys));
        }
        return reason.toString();
    }

    /** 报表特征值的人类可读描述，避免把空数组说成“非空” */
    private String describeLinkEntitys(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JSONArray array) {
            return array.isEmpty() ? "[]（空）" : "非空";
        }
        return String.valueOf(value);
    }

    /**
     * 在返回结构中递归查找某个键（报表特征字段的位置各版本不一）
     */
    private Object findDeep(Object node, String key) {
        if (node instanceof JSONObject obj) {
            if (obj.containsKey(key)) {
                return obj.get(key);
            }
            for (Object value : obj.values()) {
                Object found = findDeep(value, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray array) {
            for (Object item : array) {
                Object found = findDeep(item, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 登录并调用 QueryBusinessInfo，返回原始响应体（字段查询与类型识别共用）
     */
    private String fetchBusinessInfo(KingdeeFormFieldQueryDTO dto) {
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

        log.debug("金蝶 QueryBusinessInfo 原始返回(FormId={}): {}",
                dto.getFormId(), StrUtil.brief(responseBody, 4000));
        return responseBody;
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
        JSONObject root = parseRoot(responseBody);
        assertSuccess(root, formId);

        List<Map<String, Object>> merged = extractFields(root);
        if (merged.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "未能从返回结果中识别出字段定义，原始返回片段: " + StrUtil.brief(responseBody, 200));
        }
        return merged;
    }

    /**
     * 响应体 -> JSON 根节点：拦截空响应、网关 HTML 错误页与非法 JSON
     */
    private JSONObject parseRoot(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金蝶元数据接口返回为空");
        }

        // 网关/代理返回 HTML 错误页时先拦掉，否则 Hutool 会宽容地解析成空对象，报错信息会误导排查
        String trimmed = responseBody.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶元数据返回不是合法 JSON（可能被网关或代理拦截），片段: " + StrUtil.brief(responseBody, 120));
        }

        try {
            return JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶元数据返回不是合法 JSON: " + StrUtil.brief(responseBody, 120));
        }
    }

    /**
     * 从返回结构中抽取并去重字段定义（报表类表单会返回空列表，不视为异常）
     */
    private List<Map<String, Object>> extractFields(JSONObject root) {
        List<JSONObject> candidates = new ArrayList<>();
        collectFieldCandidates(root, candidates);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (JSONObject field : candidates) {
            String key = firstNonBlank(field, KEY_FIELDS);
            if (StrUtil.isBlank(key) || !key.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                continue;
            }
            String label = extractLabel(field);
            if (StrUtil.isNotBlank(label) && label.equalsIgnoreCase(key)) {
                label = null;
            }
            String typeHint = describeFieldType(field);
            String type = resolveFieldType(field, key);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("label", StrUtil.blankToDefault(label, key));
            item.put("type", type);
            item.put("rawType", typeHint);
            merged.putIfAbsent(key.toUpperCase(), item);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 取中文名：金蝶把名称放在 Name 数组里，形如 [{"Key":2052,"Value":"单据编号"}]
     */
    private String extractLabel(JSONObject field) {
        Object name = field.get("Name");
        if (name instanceof JSONArray array) {
            String fallback = null;
            for (Object item : array) {
                if (!(item instanceof JSONObject entry)) {
                    continue;
                }
                String value = entry.getStr("Value");
                if (StrUtil.isBlank(value)) {
                    continue;
                }
                Integer lang = entry.getInt("Key");
                if (lang != null && lang == 2052) {
                    return value.trim();
                }
                if (fallback == null) {
                    fallback = value.trim();
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } else if (name instanceof String text && StrUtil.isNotBlank(text)) {
            return text.trim();
        }
        // 兜底：部分版本用 Caption 存中文名
        String caption = field.getStr("Caption");
        return StrUtil.isBlank(caption) ? null : caption.trim();
    }

    /**
     * 字段类型推断的「人类可读」说明，用于前端展示
     */
    private String describeFieldType(JSONObject field) {
        if (field.get("Extends") instanceof JSONArray ext && !ext.isEmpty()) {
            return "枚举";
        }
        if (StrUtil.isNotBlank(field.getStr("LookUpObjectFormId"))) {
            return "基础资料";
        }
        Integer editlen = field.getInt("Editlen");
        if (editlen != null && editlen > 0) {
            return "文本(" + editlen + ")";
        }
        String fieldType = field.getStr("FieldType");
        return StrUtil.blankToDefault(fieldType, "");
    }

    /**
     * 依据元数据信号定类型，信号不足时回退到按字段名推断
     */
    private String resolveFieldType(JSONObject field, String key) {
        // 1. 枚举字段：返回的是编码字符串
        if (field.get("Extends") instanceof JSONArray ext && !ext.isEmpty()) {
            return "VARCHAR(32)";
        }
        // 2. 基础资料：裸字段返回内码(数字)，点号子字段返回编码(字符串)
        if (StrUtil.isNotBlank(field.getStr("LookUpObjectFormId"))) {
            return key.contains(".") ? "VARCHAR(255)" : "BIGINT";
        }
        // 3. 文本长度可知
        Integer editlen = field.getInt("Editlen");
        if (editlen != null && editlen > 0) {
            return "VARCHAR(" + Math.min(editlen, 255) + ")";
        }
        // 4. 其它（日期/金额/数量等）用字段名推断，日期一律 DATETIME 避免截断时分秒
        return inferTypeByKey(key);
    }

    /**
     * 只认真正的字段节点：Entry（单据体）节点没有 FieldName/FieldType，借此排除
     */
    private boolean looksLikeField(JSONObject obj) {
        if (StrUtil.isNotBlank(obj.getStr("FieldName"))) {
            return true;
        }
        if (obj.get("FieldType") != null || obj.get("ElementType") != null) {
            return true;
        }
        // 兼容其它版本：带 Caption 的扁平结构
        if (StrUtil.isNotBlank(obj.getStr("Caption"))) {
            return true;
        }
        // 兼容其它版本：有标识键 + 字符串型 Name（单据体节点的 Name 是数组，会被排除）
        return firstNonBlank(obj, KEY_FIELDS) != null && obj.get("Name") instanceof String;
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
     * 递归查找字段节点
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
     * 金蝶字段类型字符串 -> 本项目临时表类型白名单（供其它版本使用）
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
     * 按字段名推断类型（元数据信号不足时的兜底，与前端「推断类型」保持同一口径）。
     *
     * <p>日期统一推 DATETIME 而非 DATE：金蝶返回形如 2026-11-09T10:30:00，
     * 落 DATE 列会静默丢掉时分秒。
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
        if (leaf.contains("DATE") || leaf.contains("TIME")) {
            return "DATETIME";
        }
        // 金蝶布尔字段习惯用 FIsXxx 命名（如 FIsIncludedTax 含税、FIsFreeGift 赠品），
        // 必须放在金额规则之前，否则含 "TAX" 的布尔字段会被误判成 DECIMAL
        if (leaf.startsWith("FIS") || leaf.startsWith("IS") || leaf.contains("INCLUDED")
                || leaf.contains("EXCLUDED")) {
            return "VARCHAR(16)";
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
