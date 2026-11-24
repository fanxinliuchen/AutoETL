package com.dhp.etl.core.connector;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 云表 OpenAPI 鉴权。
 *
 * <p>签名规则（每次请求都要现算，不能缓存）：
 * <pre>
 *   sign = MD5(AppKey + timestamp).toUpperCase()
 *   x-eversheet-request-sign: {sign},{timestamp}      // 时间戳 6 分钟内有效
 *   x-eversheet-application-name: {appName}
 *   x-eversheet-session-token: {token}                // 登入请求留空
 * </pre>
 *
 * <p>登入接口 {@code POST /{enpId}/openapi/1.0/login}，formData 一个参数 {@code loginJson}，
 * 密码按 MD5 32 位大写提交；成功返回根节点 {@code token}，失败直接 500。
 */
@Slf4j
@Component
public class YunbiaoAuthProvider {

    /** 登入接口相对路径（enpId 已含在服务地址里） */
    private static final String LOGIN_PATH = "/openapi/1.0/login";

    /**
     * 登录并返回可直接复用的请求头（含 token）
     */
    public String login(ApiConnConfig cfg, StringBuilder logBuffer) {
        String url = cfg.yunbiaoBaseUrl() + LOGIN_PATH;
        String passwordMd5 = SecureUtil.md5(cfg.password()).toUpperCase();

        JSONObject loginJson = new JSONObject()
                .set("account", cfg.userName())
                .set("password", passwordMd5);

        if (logBuffer != null) {
            logBuffer.append("[云表-Auth] 正在登录: ").append(url).append("\n");
        }

        try (HttpResponse response = HttpRequest.post(url)
                .header("Accept", "application/json")
                .header("X-Eversheet-Application-Name", cfg.appName())
                .header("X-Eversheet-Request-Sign", signHeader(cfg.appKey()))
                .form("loginJson", loginJson.toString())
                .timeout(cfg.timeoutMs())
                .execute()) {

            String body = response.body();
            if (!response.isOk()) {
                // 云表登录失败直接返 500，正文里通常带原因
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "云表登录失败（HTTP " + response.getStatus() + "）"
                                + "[POST " + url + "]: " + describeError(body));
            }
            String token = extractToken(body);
            if (StrUtil.isBlank(token)) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "云表登录返回里没有 token: " + StrUtil.brief(body, 200));
            }
            if (logBuffer != null) {
                logBuffer.append("[云表-Auth] 登录成功，token: ").append(StrUtil.hide(token, 6, 6)).append("\n");
            }
            return token;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("云表登录异常: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "云表登录异常: " + e.getMessage());
        }
    }

    /**
     * 业务请求的公共头：签名每次现算，token 由调用方传入
     */
    public void applyHeaders(HttpRequest request, ApiConnConfig cfg, String token) {
        request.header("Accept", "application/json");
        request.header("X-Eversheet-Application-Name", cfg.appName());
        request.header("X-Eversheet-Request-Sign", signHeader(cfg.appKey()));
        if (StrUtil.isNotBlank(token)) {
            request.header("X-Eversheet-Session-Token", token);
        }
    }

    /**
     * 签名头：{@code sign,timestamp}
     */
    static String signHeader(String appKey) {
        long timestamp = System.currentTimeMillis();
        return sign(appKey, timestamp) + "," + timestamp;
    }

    /** 便于单测：固定时间戳下签名结果可复现 */
    static String sign(String appKey, long timestamp) {
        // 注意：文档正文写「AppKey + 时间戳」，但文档自带的示例是时间戳在前。
        // 用示例验算（key=058457b7-…, ts=1389085779854 → F3DFAE8C1B952779ED068B9DB66BE258）
        // 只有 ts+key 能对上，按正文拼接会被服务端判「签名验证不匹配」。
        return SecureUtil.md5(timestamp + appKey).toUpperCase();
    }

    /**
     * 把云表的错误应答翻成人话。
     *
     * <p>云表失败时返回 {@code {"Exception":{"code":...,"msg":...,"detail":...}}}，
     * 原文里的中文是 Unicode 转义形式，直接贴到界面上一屏反斜杠没法读。
     * 这里解出中文并按「code + detail」拼一行。
     */
    static String describeError(String body) {
        if (StrUtil.isBlank(body)) {
            return "（响应为空）";
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONObject ex = root.getJSONObject("Exception");
            if (ex != null) {
                String detail = StrUtil.trimToEmpty(ex.getStr("detail"));
                String msg = StrUtil.trimToEmpty(ex.getStr("msg"));
                String text = StrUtil.isNotBlank(detail) ? detail : msg;
                return "code=" + StrUtil.nullToEmpty(ex.getStr("code"))
                        + " " + text.replaceAll("\\s+", " ").trim();
            }
        } catch (Exception ignored) {
            // 非 JSON 走下面的截断
        }
        return StrUtil.brief(body, 200);
    }

    /**
     * 从登入返回里取 token。文档示例是根节点 token，兼容 Result 包裹的少数版本。
     */
    static String extractToken(String body) {
        if (StrUtil.isBlank(body)) {
            return null;
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            String token = root.getStr("token");
            if (StrUtil.isNotBlank(token)) {
                return token;
            }
            JSONObject result = root.getJSONObject("Result");
            if (result != null) {
                token = result.getStr("token");
                if (StrUtil.isNotBlank(token)) {
                    return token;
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 交给上层报错
        }
        return null;
    }
}
