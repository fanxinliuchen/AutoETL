package com.dhp.etl.core.connector;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.mapper.EtlDatasourceMapper;
import com.dhp.etl.util.CipherUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接注册中心的运行时入口：按类型拿到「数据源」或「鉴权会话」。
 *
 * <p>缓存只存**会话凭据**（cookie / token），不存请求头——云表的签名带时间戳，6 分钟窗口，
 * 每次请求必须现算，缓存头会直接过期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionManager {

    /** 云表 token 空闲 15 分钟过期，留 1 分钟余量提前重登 */
    private static final long YUNBIAO_IDLE_EXPIRE_MS = 14 * 60 * 1000L;

    /** 金蝶 cookie 寿命未知，保守按 30 分钟重登一次 */
    private static final long KINGDEE_IDLE_EXPIRE_MS = 30 * 60 * 1000L;

    /** 敏感字段在接口回显时的占位符，前端原样提交表示"不修改" */
    public static final String MASK = "********";

    private final EtlDatasourceMapper datasourceMapper;
    private final CipherUtil cipherUtil;
    private final KingdeeAuthProvider kingdeeAuthProvider;
    private final YunbiaoAuthProvider yunbiaoAuthProvider;

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    /** 会话凭据 + 最后使用时间（用于空闲过期判定） */
    private record Session(String credential, long lastUsedAt) {
    }

    public EtlDatasource load(Long connId) {
        EtlDatasource conn = connId == null ? null : datasourceMapper.selectById(connId);
        if (conn == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "连接未找到: id=" + connId);
        }
        if (conn.getStatus() != null && conn.getStatus() == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "连接已停用: " + conn.getDsName());
        }
        return conn;
    }

    public ConnTypeEnum typeOf(EtlDatasource conn) {
        return ConnTypeEnum.of(conn == null ? null : conn.getConnType());
    }

    /** 解密连接配置；仅 API 类连接可调用 */
    public ApiConnConfig apiConfig(EtlDatasource conn) {
        ConnTypeEnum type = typeOf(conn);
        if (!type.isApi()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该连接不是 API 类连接: " + conn.getDsName());
        }
        return ApiConnConfig.parse(type, cipherUtil.decrypt(conn.getConfigCipher()));
    }

    /**
     * 取鉴权请求头（含凭据）。
     *
     * @param connId    连接 ID
     * @param logBuffer 执行日志
     */
    public Map<String, String> authHeaders(Long connId, StringBuilder logBuffer) {
        EtlDatasource conn = load(connId);
        ConnTypeEnum type = typeOf(conn);
        if (!type.isApi()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该连接不是 API 类连接: " + conn.getDsName());
        }
        ApiConnConfig cfg = apiConfig(conn);

        Session session = sessions.get(connId);
        long now = System.currentTimeMillis();
        long ttl = type == ConnTypeEnum.IYUNBIAO ? YUNBIAO_IDLE_EXPIRE_MS : KINGDEE_IDLE_EXPIRE_MS;

        if (session == null || now - session.lastUsedAt() > ttl) {
            String credential = type == ConnTypeEnum.IYUNBIAO
                    ? yunbiaoAuthProvider.login(cfg, logBuffer)
                    : kingdeeAuthProvider.login(cfg, logBuffer);
            session = new Session(credential, now);
            sessions.put(connId, session);
            log.debug("[连接] {} 已建立会话 connId={}", type.getDesc(), connId);
        } else {
            sessions.put(connId, new Session(session.credential(), now));
        }

        if (type == ConnTypeEnum.IYUNBIAO) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("X-Eversheet-Application-Name", cfg.appName());
            headers.put("X-Eversheet-Request-Sign", YunbiaoAuthProvider.signHeader(cfg.appKey()));
            headers.put("X-Eversheet-Session-Token", session.credential());
            return headers;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Cookie", session.credential());
        return headers;
    }

    /** 会话失效：下次请求重登 */
    public void invalidate(Long connId) {
        if (connId != null) {
            sessions.remove(connId);
        }
    }

    /**
     * 测试连接：真登录一次（不走缓存），返回可直接展示的结果
     */
    public Map<String, Object> testApiLogin(Long connId) {
        EtlDatasource conn = load(connId);
        ApiConnConfig cfg = apiConfig(conn);
        Map<String, Object> result = doTestLogin(cfg);
        sessions.put(connId, new Session((String) result.get("credential"), System.currentTimeMillis()));
        result.remove("credential");
        return result;
    }

    /**
     * 未保存的连接也能先测：入参是明文配置 JSON。
     *
     * @param connId 已有连接 ID；传入时先把打码 / 为空的敏感字段换成库里的真实值，
     *               否则编辑态一测必然报「签名验证不匹配 / 账号密码错误」
     */
    public Map<String, Object> testApiLogin(ConnTypeEnum type, String plainConfigJson, Long connId) {
        String plain = plainConfigJson;
        if (connId != null) {
            EtlDatasource old = datasourceMapper.selectById(connId);
            if (old != null && StrUtil.isNotBlank(old.getConfigCipher())) {
                plain = mergeSecretsPlain(old.getConfigCipher(), plainConfigJson);
            }
        }
        ApiConnConfig cfg = ApiConnConfig.parse(type, plain);
        Map<String, Object> result = doTestLogin(cfg);
        result.remove("credential");
        return result;
    }

    public Map<String, Object> testApiLogin(ConnTypeEnum type, String plainConfigJson) {
        return testApiLogin(type, plainConfigJson, null);
    }

    private Map<String, Object> doTestLogin(ApiConnConfig cfg) {
        StringBuilder log = new StringBuilder();
        String credential = cfg.getType() == ConnTypeEnum.IYUNBIAO
                ? yunbiaoAuthProvider.login(cfg, log)
                : kingdeeAuthProvider.login(cfg, log);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", cfg.getType().getDesc() + " 连接成功，鉴权已通过");
        result.put("log", log.toString());
        result.put("credential", credential);
        return result;
    }

    /**
     * 配置回显：敏感字段打码，其余原样（前端要能编辑服务地址/账号）
     */
    public Map<String, Object> maskedConfig(Long connId) {
        EtlDatasource conn = load(connId);
        ApiConnConfig cfg = apiConfig(conn);
        JSONObject plain = JSONUtil.parseObj(cipherUtil.decrypt(conn.getConfigCipher()));
        // queryUrl 已废弃（取哪张单据由流程的接口地址决定），老数据不再回显
        plain.remove("queryUrl");
        if (plain.containsKey("password")) {
            plain.set("password", MASK);
        }
        if (plain.containsKey("appKey")) {
            plain.set("appKey", MASK);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connType", cfg.getType().getCode());
        result.put("config", plain);
        return result;
    }

    /**
     * 列表展示用的摘要：连接目标 + 账号。
     *
     * <p>API 类连接的地址在加密的 {@code config_cipher} 里，前端拿不到；
     * 这里只解出**地址类**字段，密码 / AppKey 一律不出。
     */
    public Map<String, Object> summary(EtlDatasource conn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", conn.getId());
        row.put("dsName", conn.getDsName());
        row.put("connType", conn.getConnType());
        row.put("status", conn.getStatus());
        row.put("remark", conn.getRemark());

        ConnTypeEnum type = typeOf(conn);
        if (!type.isApi()) {
            int port = conn.getPort() == null ? (type == ConnTypeEnum.POSTGRESQL ? 5432 : 3306) : conn.getPort();
            row.put("connectionTarget", String.format("%s:%d / %s",
                    StrUtil.blankToDefault(conn.getHost(), "-"),
                    port,
                    StrUtil.blankToDefault(conn.getDatabaseName(), "-")));
            row.put("account", conn.getUsername());
            // 回传给前端做「编辑」回显：只有连接目标会害得编辑时用默认值覆盖真实配置
            row.put("host", conn.getHost());
            row.put("port", port);
            row.put("databaseName", conn.getDatabaseName());
            row.put("encoding", conn.getEncoding());
            row.put("driverClass", conn.getDriverClass());
            // 连接串不含密码，回传用于编辑时还原 schema 等 URL 级参数
            row.put("jdbcUrl", conn.getJdbcUrl());
            return row;
        }

        try {
            ApiConnConfig cfg = apiConfig(conn);
            if (type == ConnTypeEnum.IYUNBIAO) {
                row.put("connectionTarget", cfg.yunbiaoBaseUrl());
            } else {
                // 金蝶只关心服务根，具体单据地址在流程里
                row.put("connectionTarget", serviceRoot(cfg.kingdeeLoginUrl()));
            }
            row.put("account", cfg.userName());
        } catch (Exception e) {
            // 单条配置坏掉不能让整个列表打不开
            row.put("connectionTarget", "配置异常：" + StrUtil.brief(e.getMessage(), 60));
            row.put("account", "-");
        }
        return row;
    }

    /**
     * 从完整接口地址截出服务根：
     * {@code http://39.98.122.240/K3Cloud/Kingdee.BOS...kdsvc} → {@code http://39.98.122.240/K3Cloud/}
     */
    static String serviceRoot(String url) {
        if (StrUtil.isBlank(url)) {
            return "-";
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return url;
            }
            String base = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            for (String segment : StrUtil.nullToEmpty(uri.getPath()).split("/")) {
                if (StrUtil.isNotBlank(segment)) {
                    return base + "/" + segment + "/";
                }
            }
            return base + "/";
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 保存时合并敏感字段：提交空值或占位符表示沿用旧值（返回加密串）
     */
    public String mergeSecrets(String oldCipher, String incomingPlainJson) {
        return cipherUtil.encrypt(mergeSecretsPlain(oldCipher, incomingPlainJson));
    }

    /**
     * 同上，返回明文 JSON。
     *
     * <p>「测试登录」必须走这里：编辑态的弹窗里密码 / AppKey 回显的是 {@link #MASK}，
     * 直接拿去登录等于用 {@code ********} 当密钥，必然报「签名验证不匹配」。
     */
    public String mergeSecretsPlain(String oldCipher, String incomingPlainJson) {
        JSONObject incoming;
        try {
            incoming = JSONUtil.parseObj(StrUtil.blankToDefault(incomingPlainJson, "{}"));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "连接配置不是合法 JSON: " + e.getMessage());
        }
        if (StrUtil.isNotBlank(oldCipher)) {
            String oldPlain = cipherUtil.decrypt(oldCipher);
            if (StrUtil.isNotBlank(oldPlain)) {
                JSONObject old = JSONUtil.parseObj(oldPlain);
                for (String secret : new String[]{"password", "appKey"}) {
                    if (!incoming.containsKey(secret)
                            || StrUtil.isBlank(incoming.getStr(secret))
                            || MASK.equals(incoming.getStr(secret))) {
                        if (old.containsKey(secret)) {
                            incoming.set(secret, old.getStr(secret));
                        }
                    }
                }
            }
        }
        // 顺手清掉废弃字段：老连接保存一次就干净了
        incoming.remove("queryUrl");
        return incoming.toString();
    }
}
