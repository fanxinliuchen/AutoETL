package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import com.dhp.etl.core.connector.ApiConnConfig;
import com.dhp.etl.core.connector.ConnectionManager;
import com.dhp.etl.core.connector.KingdeeAuthProvider;
import com.dhp.etl.core.connector.YunbiaoAuthProvider;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.mapper.EtlDatasourceMapper;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import com.dhp.etl.util.CipherUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 金蝶会话失效识别与重登缓存失效测试：
 * <ul>
 *   <li>只有明确要求重新登录的文案才触发重登续跑，业务错误不能误判；</li>
 *   <li>重登前必须 invalidate 连接级会话缓存，否则 ConnectionManager 会把未超本地 TTL
 *       的已失效 Cookie 原样返回并续期，导致分页/单次重试永远命中死会话。</li>
 * </ul>
 */
class WebApiExtractorSessionTest {

    private static final Long CONN_ID = 2122520197024821249L;
    private static final String STALE_COOKIE = "kdservice-sessionid=STALE_COOKIE";
    private static final String FRESH_COOKIE = "kdservice-sessionid=FRESH_COOKIE";
    private static final String SESSION_EXPIRED_BODY =
            "[[{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"Message\":\"会话信息已丢失，请重新登录\"}]}}}]]";
    private static final String BUSINESS_ERROR_BODY =
            "[[{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,\"Errors\":[{\"Message\":\"元数据中标识为 FBadField 的字段不存在\"}]}}}]]";
    private static final String VALID_PAGE_BODY = "[[1001, \"18*1220*2440mm\"]]";

    @Test
    @DisplayName("会话失效文案能识别")
    void sessionExpiredHints() {
        assertTrue(WebApiExtractor.isSessionExpired("金蝶返回业务错误: 会话信息已丢失，请重新登录"));
        assertTrue(WebApiExtractor.isSessionExpired("会话已过期"));
        assertTrue(WebApiExtractor.isSessionExpired("会话超时，请重新登入"));
        assertTrue(WebApiExtractor.isSessionExpired("登录已过期"));
        assertTrue(WebApiExtractor.isSessionExpired("token 过期"));
    }

    @Test
    @DisplayName("业务错误与空值不算会话失效")
    void businessErrorsNotSession() {
        assertFalse(WebApiExtractor.isSessionExpired("元数据中标识为 FCustMatlD 的字段不存在"));
        assertFalse(WebApiExtractor.isSessionExpired("FDate 格式不正确"));
        assertFalse(WebApiExtractor.isSessionExpired("分页第 1 页拉取失败"));
        assertFalse(WebApiExtractor.isSessionExpired(null));
        assertFalse(WebApiExtractor.isSessionExpired(""));
    }

    @Test
    @DisplayName("分页拉取遇到缓存中的失效会话时，必须清缓存重登并用新 Cookie 重试当前页")
    void pagedExtractInvalidatesCachedSessionAndRetries() {
        KingdeeAuthProvider kingdeeAuth = mock(KingdeeAuthProvider.class);
        when(kingdeeAuth.login(any(ApiConnConfig.class), any()))
                .thenReturn(STALE_COOKIE)
                .thenReturn(FRESH_COOKIE);

        ConnectionManager connectionManager = newRealConnectionManager(kingdeeAuth);
        // 模拟之前已有请求把旧 Cookie 写进缓存（未到本地 30 分钟空闲阈值，但服务端已过期）
        connectionManager.authHeaders(CONN_ID, new StringBuilder());

        List<String> sentCookies = new ArrayList<>();
        WebApiExtractor extractor = newStubExtractor(connectionManager, sentCookies, cookie ->
                STALE_COOKIE.equals(cookie) ? SESSION_EXPIRED_BODY : VALID_PAGE_BODY);

        EtlPipeline pipeline = newKingdeePipeline(
                "{\"enabled\":true,\"pageSize\":2000,\"maxPages\":10,\"start\":0,\"step\":2000,\"endWhenLessThan\":2000,\"offsetPath\":\"data.StartRow\",\"limitPath\":\"data.Limit\"}");
        StringBuilder logBuffer = new StringBuilder();

        List<Map<String, Object>> rows = extractor.extract(pipeline, logBuffer);

        assertEquals(1, rows.size());
        assertEquals(1001, rows.get(0).get("FEntity_FENTRYID"));
        assertEquals("18*1220*2440mm", rows.get(0).get("FMateriaModel"));
        assertEquals(List.of(STALE_COOKIE, FRESH_COOKIE), sentCookies);
        verify(kingdeeAuth, times(2)).login(any(ApiConnConfig.class), any());
        assertTrue(logBuffer.toString().contains("重新登录后重试该页"));
    }

    @Test
    @DisplayName("单次拉取遇到缓存中的失效会话时，同样清缓存重登并重试一次")
    void singleShotExtractInvalidatesCachedSessionAndRetries() {
        KingdeeAuthProvider kingdeeAuth = mock(KingdeeAuthProvider.class);
        when(kingdeeAuth.login(any(ApiConnConfig.class), any()))
                .thenReturn(STALE_COOKIE)
                .thenReturn(FRESH_COOKIE);

        ConnectionManager connectionManager = newRealConnectionManager(kingdeeAuth);
        connectionManager.authHeaders(CONN_ID, new StringBuilder());

        List<String> sentCookies = new ArrayList<>();
        WebApiExtractor extractor = newStubExtractor(connectionManager, sentCookies, cookie ->
                STALE_COOKIE.equals(cookie) ? SESSION_EXPIRED_BODY : VALID_PAGE_BODY);

        EtlPipeline pipeline = newKingdeePipeline("{\"enabled\":false}");
        StringBuilder logBuffer = new StringBuilder();

        List<Map<String, Object>> rows = extractor.extract(pipeline, logBuffer);

        assertEquals(1, rows.size());
        assertEquals("18*1220*2440mm", rows.get(0).get("FMateriaModel"));
        assertEquals(List.of(STALE_COOKIE, FRESH_COOKIE), sentCookies);
        verify(kingdeeAuth, times(2)).login(any(ApiConnConfig.class), any());
        assertTrue(logBuffer.toString().contains("单次请求会话已失效"));
    }

    @Test
    @DisplayName("单次拉取遇到普通业务错误不触发重登")
    void singleShotBusinessErrorDoesNotTriggerReAuth() {
        KingdeeAuthProvider kingdeeAuth = mock(KingdeeAuthProvider.class);
        when(kingdeeAuth.login(any(ApiConnConfig.class), any())).thenReturn(STALE_COOKIE);

        ConnectionManager connectionManager = newRealConnectionManager(kingdeeAuth);
        List<String> sentCookies = new ArrayList<>();
        WebApiExtractor extractor = newStubExtractor(connectionManager, sentCookies, cookie -> BUSINESS_ERROR_BODY);

        EtlPipeline pipeline = newKingdeePipeline("{\"enabled\":false}");

        BizException ex = assertThrows(BizException.class,
                () -> extractor.extract(pipeline, new StringBuilder()));

        assertTrue(ex.getMessage().contains("FBadField"));
        assertEquals(List.of(STALE_COOKIE), sentCookies);
        verify(kingdeeAuth, times(1)).login(any(ApiConnConfig.class), any());
    }

    @Test
    @DisplayName("reAuth 严格按先 invalidate 后 authHeaders 的顺序刷新 Cookie")
    void reAuthInvalidatesBeforeReAuthenticating() {
        ConnectionManager mockConnMgr = mock(ConnectionManager.class);
        EtlDatasource conn = new EtlDatasource();
        conn.setId(CONN_ID);
        conn.setConnType(ConnTypeEnum.KINGDEE_K3.getCode());
        when(mockConnMgr.load(CONN_ID)).thenReturn(conn);
        when(mockConnMgr.typeOf(conn)).thenReturn(ConnTypeEnum.KINGDEE_K3);
        when(mockConnMgr.authHeaders(any(), any())).thenReturn(Map.of("Cookie", FRESH_COOKIE));

        WebApiExtractor extractor = new WebApiExtractor(
                mock(EtlKingdeeCredentialService.class),
                mock(RequestVarResolver.class),
                mockConnMgr);

        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setSourceDsId(CONN_ID);
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", STALE_COOKIE);

        extractor.reAuth(pipeline, headers, new StringBuilder());

        InOrder order = inOrder(mockConnMgr);
        order.verify(mockConnMgr).invalidate(CONN_ID);
        order.verify(mockConnMgr).authHeaders(any(), any());
        assertEquals(FRESH_COOKIE, headers.get("Cookie"));
    }

    private ConnectionManager newRealConnectionManager(KingdeeAuthProvider kingdeeAuth) {
        EtlDatasourceMapper dsMapper = mock(EtlDatasourceMapper.class);
        CipherUtil cipherUtil = mock(CipherUtil.class);
        YunbiaoAuthProvider yunbiaoAuth = mock(YunbiaoAuthProvider.class);

        EtlDatasource conn = new EtlDatasource();
        conn.setId(CONN_ID);
        conn.setDsName("金蝶K3测试连接");
        conn.setConnType(ConnTypeEnum.KINGDEE_K3.getCode());
        conn.setStatus(1);
        conn.setConfigCipher("CIPHER");

        when(dsMapper.selectById(CONN_ID)).thenReturn(conn);
        when(cipherUtil.decrypt("CIPHER")).thenReturn(
                "{\"loginUrl\":\"http://127.0.0.1/K3Cloud/ValidateUser.kdsvc\",\"acctID\":\"acct\",\"userName\":\"bi\",\"password\":\"pwd\",\"lcid\":2052}");

        return new ConnectionManager(dsMapper, cipherUtil, kingdeeAuth, yunbiaoAuth);
    }

    private WebApiExtractor newStubExtractor(ConnectionManager connectionManager,
                                             List<String> sentCookies,
                                             java.util.function.Function<String, String> responder) {
        RequestVarResolver varResolver = mock(RequestVarResolver.class);
        when(varResolver.resolve(any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        return new WebApiExtractor(mock(EtlKingdeeCredentialService.class), varResolver, connectionManager) {
            @Override
            String sendOnce(String apiUrl, String method, Map<String, String> headers, String body, boolean formBody) {
                String cookie = headers.get("Cookie");
                sentCookies.add(cookie);
                return responder.apply(cookie);
            }
        };
    }

    private EtlPipeline newKingdeePipeline(String paginationJson) {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setSourceDsId(CONN_ID);
        pipeline.setApiUrl("http://127.0.0.1/k3cloud/ExecuteBillQuery.common.kdsvc");
        pipeline.setApiMethod("POST");
        pipeline.setApiDataPath("$.*");
        pipeline.setApiBody("{\"data\":{\"FormId\":\"SAL_OUTSTOCK\",\"FieldKeys\":\"FEntity_FENTRYID,FMateriaModel\",\"Limit\":2000,\"StartRow\":0}}");
        pipeline.setApiPagination(paginationJson);
        return pipeline;
    }
}
