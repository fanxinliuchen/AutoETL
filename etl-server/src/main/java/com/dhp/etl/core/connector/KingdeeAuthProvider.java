package com.dhp.etl.core.connector;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 金蝶云星空连接鉴权（连接注册中心版）。
 *
 * <p>与 {@code WebApiExtractor#executePreAuth} 的 KINGDEE_K3 分支行为一致：
 * ValidateUser 换 cookie，校验 LoginResultType，密码留空时回落到已保存凭证。
 *
 * <p>此处独立实现而不是调用抽取器，是为了避免
 * 「WebApiExtractor → ConnectionManager → 本类 → WebApiExtractor」的依赖环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KingdeeAuthProvider {

    private final EtlKingdeeCredentialService credentialService;

    /**
     * 登录并返回会话 Cookie
     */
    public String login(ApiConnConfig cfg, StringBuilder logBuffer) {
        String userName = cfg.userName();
        String password = cfg.password();
        if (StrUtil.isBlank(password)) {
            String saved = credentialService.resolvePassword(cfg.acctId(), userName);
            if (StrUtil.isNotBlank(saved)) {
                password = saved;
                if (logBuffer != null) {
                    logBuffer.append("[金蝶-Auth] 已使用保存的登录凭证（密码不回显）\n");
                }
            }
        }
        if (StrUtil.isBlank(password)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "金蝶连接未配置密码，且未找到与之匹配的已保存凭证（账套或用户名不一致）");
        }

        JSONObject body = new JSONObject()
                .set("acctID", StrUtil.nullToEmpty(cfg.acctId()))
                .set("userName", userName)
                .set("password", password)
                .set("lcid", cfg.lcid());

        String loginUrl = cfg.kingdeeLoginUrl();
        if (logBuffer != null) {
            logBuffer.append("[金蝶-Auth] 正在向登录接口发送鉴权请求: ").append(loginUrl).append("\n");
        }

        try (HttpResponse response = HttpRequest.post(loginUrl)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(30000)
                .execute()) {

            if (!response.isOk()) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "金蝶登录失败，HTTP 状态码: " + response.getStatus());
            }
            String respBody = response.body();
            Integer resultType = null;
            try {
                resultType = JSONUtil.parseObj(respBody).getInt("LoginResultType");
            } catch (Exception ignored) {
                // 非 JSON 交给下面的 cookie 判定
            }
            if (resultType != null && resultType != 1) {
                String message = "";
                try {
                    message = JSONUtil.parseObj(respBody).getStr("Message");
                } catch (Exception ignored) {
                }
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR,
                        "金蝶登录验证未通过: " + StrUtil.blankToDefault(message, "LoginResultType=" + resultType));
            }

            String cookie = joinSetCookie(response.headerList("Set-Cookie"));
            if (StrUtil.isBlank(cookie)) {
                throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "金蝶登录成功但未返回会话 Cookie");
            }
            if (logBuffer != null) {
                logBuffer.append("[金蝶-Auth] 登录成功，会话 Cookie: ").append(StrUtil.brief(cookie, 40)).append("\n");
            }
            return cookie;
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("金蝶连接登录异常: {}", e.getMessage());
            throw new BizException(ErrorCode.ETL_EXECUTE_ERROR, "金蝶登录异常: " + e.getMessage());
        }
    }

    private String joinSetCookie(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder();
        for (String item : setCookies) {
            String one = item.split(";")[0].trim();
            if (buffer.length() > 0) {
                buffer.append("; ");
            }
            buffer.append(one);
        }
        return buffer.toString();
    }
}
