package com.dhp.etl.core.connector;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.enums.ConnTypeEnum;

/**
 * 非 MySQL 连接的配置（{@code t_etl_datasource.config_cipher} 解密后的 JSON）。
 *
 * <p>类型专属参数集中收在 JSON 里，不再往表上加列——每接一种系统只加一个 {@link ConnTypeEnum}，
 * 表结构不动。取值方法按类型命名，避免"通用 get('url')"这种看不出语义的写法。
 */
public class ApiConnConfig {

    private final ConnTypeEnum type;
    private final JSONObject raw;

    private ApiConnConfig(ConnTypeEnum type, JSONObject raw) {
        this.type = type;
        this.raw = raw;
    }

    public static ApiConnConfig parse(ConnTypeEnum type, String plainJson) {
        if (StrUtil.isBlank(plainJson)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "连接配置为空：" + type.getDesc());
        }
        JSONObject json;
        try {
            json = JSONUtil.parseObj(plainJson);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "连接配置不是合法 JSON: " + e.getMessage());
        }
        return new ApiConnConfig(type, json);
    }

    public ConnTypeEnum getType() {
        return type;
    }

    private String required(String key, String label) {
        String value = StrUtil.trimToEmpty(raw.getStr(key));
        if (StrUtil.isBlank(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    type.getDesc() + "连接缺少必填项：" + label);
        }
        return value;
    }

    private String optional(String key) {
        return StrUtil.trimToEmpty(raw.getStr(key));
    }

    // ===== 金蝶 =====

    /**
     * 金蝶只存**登录地址**：具体取哪个单据由流程的「接口地址」决定，
     * 连接不该替流程指定查询地址（同一套凭据会跑很多张单据）。
     */
    public String kingdeeLoginUrl() {
        return trimSlash(required("loginUrl", "登录接口地址"));
    }

    public String acctId() {
        return optional("acctID");
    }

    public String userName() {
        return required("userName", "登录账号");
    }

    public String password() {
        return StrUtil.nullToEmpty(raw.getStr("password"));
    }

    public Integer lcid() {
        return raw.getInt("lcid", 2052);
    }

    // ===== 云表 =====

    public String yunbiaoBaseUrl() {
        return trimSlash(required("baseUrl", "服务地址"));
    }

    public String appName() {
        return required("appName", "应用名称（AppName）");
    }

    public String appKey() {
        return required("appKey", "AppKey");
    }

    public int timeoutMs() {
        Integer value = raw.getInt("timeoutMs");
        return value == null || value <= 0 ? 15000 : value;
    }

    /** 服务地址末尾的斜杠必须去掉，否则拼出来是 //openapi */
    private static String trimSlash(String url) {
        if (StrUtil.isBlank(url)) {
            return url;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
