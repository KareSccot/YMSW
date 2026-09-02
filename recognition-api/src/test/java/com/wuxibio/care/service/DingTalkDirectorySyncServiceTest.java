package com.wuxibio.care.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DingTalkDirectorySyncServiceTest {

    @Mock private ExternalConnectionService connectionService;
    @Mock private SysUserMapper sysUserMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private DingTalkDirectorySyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DingTalkDirectorySyncService(connectionService, sysUserMapper);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handleDingTalkApi);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void syncUserIdsByEmployeeId_updatesLocalUserFromDingTalkJobNumber() {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setEmployeeId("DLWADMIN3");
        user.setDingtalkUserId("");

        when(connectionService.getActiveConfig("DingTalk")).thenReturn(Map.of(
                "apiBaseUrl", serverBaseUrl(),
                "appKey", "app-key",
                "appSecret", "app-secret",
                "contactRootDeptId", "1"));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));

        DingTalkDirectorySyncService.SyncResult result = service.syncUserIdsByEmployeeId();

        assertEquals(2, result.totalUsers());
        assertEquals(1, result.matchedUsers());
        assertEquals(1, result.updated());
        assertEquals(0, result.unchanged());
        assertEquals(1, result.skippedNoEmployeeId());
        assertEquals(0, result.unmatchedUsers());

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(10L, captor.getValue().getId());
        assertEquals("dt_real_dlwadmin3", captor.getValue().getDingtalkUserId());
    }

    @Test
    void syncUserIdsByEmployeeId_doesNotUpdateWhenValueUnchanged() {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setEmployeeId("DLWADMIN3");
        user.setDingtalkUserId("dt_real_dlwadmin3");

        when(connectionService.getActiveConfig("DingTalk")).thenReturn(Map.of(
                "apiBaseUrl", serverBaseUrl(),
                "appKey", "app-key",
                "appSecret", "app-secret",
                "contactDepartmentIds", "2"));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));

        DingTalkDirectorySyncService.SyncResult result = service.syncUserIdsByEmployeeId();

        assertEquals(1, result.matchedUsers());
        assertEquals(0, result.updated());
        assertEquals(1, result.unchanged());
        verify(sysUserMapper, never()).updateById((SysUser) any());
    }

    private void handleDingTalkApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/gettoken".equals(path)) {
            respond(exchange, """
                    {"errcode":0,"errmsg":"ok","access_token":"token-1"}
                    """);
            return;
        }
        if ("/topapi/v2/department/listsubid".equals(path)) {
            Map<String, Object> body = requestBody(exchange);
            long deptId = ((Number) body.get("dept_id")).longValue();
            if (deptId == 1L) {
                respond(exchange, """
                        {"errcode":0,"errmsg":"ok","result":{"dept_id_list":[2]}}
                        """);
            } else {
                respond(exchange, """
                        {"errcode":0,"errmsg":"ok","result":{"dept_id_list":[]}}
                        """);
            }
            return;
        }
        if ("/topapi/v2/user/list".equals(path)) {
            Map<String, Object> body = requestBody(exchange);
            long deptId = ((Number) body.get("dept_id")).longValue();
            if (deptId == 2L) {
                respond(exchange, """
                        {"errcode":0,"errmsg":"ok","result":{"has_more":false,"next_cursor":0,"list":[
                          {"userid":"dt_real_dlwadmin3","job_number":"DLWADMIN3"},
                          {"userid":"dt_without_job_number","name":"No Job Number"}
                        ]}}
                        """);
            } else {
                respond(exchange, """
                        {"errcode":0,"errmsg":"ok","result":{"has_more":false,"next_cursor":0,"list":[]}}
                        """);
            }
            return;
        }
        respond(exchange, "{\"errcode\":404,\"errmsg\":\"not found\"}");
    }

    private Map<String, Object> requestBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
