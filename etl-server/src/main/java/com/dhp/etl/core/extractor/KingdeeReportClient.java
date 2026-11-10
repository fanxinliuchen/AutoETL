package com.dhp.etl.core.extractor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.dto.KingdeeReportFieldQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 金蝶「分页报表取数」客户端。
 *
 * <p>报表类表单（STK_StockSummaryRpt 等）没有单据元数据，QueryBusinessInfo 取不到列定义，
 * 只能通过 {@code StockReportQueryService.GetReportData} 试取 1 行：
 * FieldKeys 留空时返回过滤方案的全部列，行对象的 key 就是字段标识。
 *
 * <p>两个易错点（都实测过）：
 * <ul>
 *   <li>接口地址的装配名分隔符是逗号，不是点号：{@code GetReportData,Kingdee.K3.SCM.WebApi.ServicesStub.common.kdsvc}。
 *       写成点号金蝶直接返回 “... no found”。</li>
 *   <li>parameters 传对象数组或 JSON 字符串数组都能被接受，这里用对象数组，便于分页配置复用。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KingdeeReportClient {

    private static final String REPORT_SERVICE_PATH =
            "/K3Cloud/Kingdee.K3.SCM.WebApi.ServicesStub.StockReportQueryService.GetReportData"
                    + ",Kingdee.K3.SCM.WebApi.ServicesStub.common.kdsvc";

    /** 把误写成点号的装配名纠正为逗号（.Kingdee.xxx.common.kdsvc -> ,Kingdee.xxx.common.kdsvc） */
    private static final Pattern ASSEMBLY_WITH_DOT =
            Pattern.compile("\\.(Kingdee\\.[A-Za-z0-9_.]+)\\.common\\.kdsvc$");

    private final WebApiExtractor webApiExtractor;

    /**
     * 试取 1 行推断报表字段
     *
     * @return fields / fieldCount / totalCount / message
     */
    public Map<String, Object> detectFields(KingdeeReportFieldQueryDTO dto) {
        String reportUrl = resolveReportUrl(dto);
        webApiExtractor.validateUrlSafety(reportUrl);

        String cookie = login(dto);

        JSONObject param = new JSONObject();
        param.set("FORMID", dto.getFormId().trim());
        param.set("FSCHEMEID", dto.getSchemeId().trim());
        param.set("QuicklyCondition", new JSONArray());
        param.set("StartRow", 0);
        // 只取 1 行：既够推断列名，又不会把整张报表拉下来
        param.set("Limit", 1);
        JSONObject payload = new JSONObject();
        payload.set("parameters", new JSONArray().put(param));

        String responseBody;
        try (HttpResponse response = HttpRequest.post(reportUrl)
                .header("Content-Type", "application/json")
                .header("Cookie", StrUtil.nullToEmpty(cookie))
                .body(payload.toString())
                .timeout(60000)
                .execute()) {
            if (!response.isOk()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "调用金蝶报表接口失败，HTTP 状态码: " + response.getStatus());
            }
            responseBody = response.body();
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "调用金蝶报表接口异常: " + e.getMessage());
        }

        log.debug("金蝶报表取数返回(FormId={}, Scheme={}): {}", dto.getFormId(), dto.getSchemeId(),
                StrUtil.brief(responseBody, 4000));

        return parseFields(responseBody);
    }

    /**
     * 解析 GetReportData 返回。金蝶的业务错误可能出现在 success=false 或纯文本 response_error 里。
     */
    Map<String, Object> parseFields(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金蝶报表接口返回为空");
        }
        String trimmed = responseBody.trim();
        if (!trimmed.startsWith("{")) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶报表接口返回异常（请检查接口地址与参数）：" + StrUtil.brief(responseBody, 200));
        }

        JSONObject root;
        try {
            root = JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶报表返回不是合法 JSON: " + StrUtil.brief(responseBody, 200));
        }

        Boolean success = root.getBool("success");
        if (Boolean.FALSE.equals(success)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    StrUtil.blankToDefault(root.getStr("message"), "金蝶报表查询失败"));
        }

        Object totalCount = root.get("TotalCount");
        Object data = root.get("data");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);

        if (!(data instanceof JSONArray rows) || rows.isEmpty()) {
            result.put("fields", new ArrayList<>());
            result.put("fieldCount", 0);
            result.put("message", "报表查询成功但没有返回数据（TotalCount=" + totalCount
                    + "），无法从结果推断字段。请先确认该过滤方案在金蝶里能查出数据"
                    + "（常见原因：方案未设默认库存组织 / 会计期间），或改用 Excel 导入字段。");
            return result;
        }

        Object first = rows.get(0);
        if (!(first instanceof JSONObject row)) {
            result.put("fields", new ArrayList<>());
            result.put("fieldCount", 0);
            result.put("message", "报表返回的是数组行，没有列名信息，无法推断字段标识；请用 Excel 导入字段。");
            return result;
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (String key : row.keySet()) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            // 报表行里只有字段标识，没有中文名；中文名留空由用户补，或 Excel 导入
            item.put("label", key);
            item.put("type", KingdeeMetadataClient.inferTypeByKey(key));
            item.put("rawType", "报表列");
            fields.add(item);
        }

        result.put("fields", fields);
        result.put("fieldCount", fields.size());
        result.put("message", "已按报表首行推断出 " + fields.size() + " 个字段");
        return result;
    }

    private String resolveReportUrl(KingdeeReportFieldQueryDTO dto) {
        String configured = StrUtil.trimToNull(dto.getReportUrl());
        if (configured != null) {
            return ASSEMBLY_WITH_DOT.matcher(configured).replaceFirst(",$1.common.kdsvc");
        }
        try {
            URI uri = new URI(dto.getLoginUrl());
            if (StrUtil.isBlank(uri.getScheme()) || StrUtil.isBlank(uri.getAuthority())) {
                throw new IllegalArgumentException("登录地址缺少协议或主机");
            }
            return uri.getScheme() + "://" + uri.getAuthority() + REPORT_SERVICE_PATH;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无法由登录地址推导报表接口: " + e.getMessage());
        }
    }

    private String login(KingdeeReportFieldQueryDTO dto) {
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
}
