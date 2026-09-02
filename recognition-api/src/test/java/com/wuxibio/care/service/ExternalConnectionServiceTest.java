package com.wuxibio.care.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.ExternalConnectionResponse;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalConnectionServiceTest {

    @Mock private ExternalConnectionMapper mapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SensitiveDataCryptoService cryptoService;
    private ExternalConnectionService service;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        cryptoService = new SensitiveDataCryptoService("unit-test-key");
        service = new ExternalConnectionService(mapper, cryptoService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of(() -> "ROLE_GLOBAL_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_encryptsSensitiveConfigValuesBeforeSaving() throws Exception {
        ExternalConnection conn = new ExternalConnection();
        conn.setType("SMTP");
        conn.setName("mail");
        conn.setIsActive(0);
        conn.setConfig("""
                {"host":"smtp.example.com","password":"plain-pass","clientSecret":"client-secret"}
                """);

        service.create(conn);

        verify(mapper).insert(any(ExternalConnection.class));
        Map<String, Object> stored = objectMapper.readValue(conn.getConfig(), new TypeReference<>() {});
        assertEquals("smtp.example.com", stored.get("host"));
        assertTrue(cryptoService.isEncrypted(String.valueOf(stored.get("password"))));
        assertTrue(cryptoService.isEncrypted(String.valueOf(stored.get("clientSecret"))));
        assertEquals("plain-pass", service.parseConfig(conn.getConfig()).get("password"));
        assertEquals("client-secret", service.parseConfig(conn.getConfig()).get("clientSecret"));
    }

    @Test
    void parseConfig_keepsLegacyPlaintextCompatible() {
        Map<String, String> cfg = service.parseConfig("""
                {"host":"smtp.example.com","password":"legacy-pass"}
                """);

        assertEquals("smtp.example.com", cfg.get("host"));
        assertEquals("legacy-pass", cfg.get("password"));
    }

    @Test
    void parseConfig_samlBearerIgnoresStaleUndecryptablePassword() throws Exception {
        SensitiveDataCryptoService otherKeyCrypto = new SensitiveDataCryptoService("other-key");
        Map<String, String> cfg = service.parseConfig(objectMapper.writeValueAsString(Map.of(
                "authType", "saml_bearer",
                "apiBaseUrl", "https://api.example.com/odata/v2",
                "apiKey", "oauth-client-id",
                "privateKeyPem", "plain-private-key",
                "password", otherKeyCrypto.encryptIfNeeded("stale-basic-password")
        )));

        assertEquals("saml_bearer", cfg.get("authType"));
        assertEquals("oauth-client-id", cfg.get("apiKey"));
        assertEquals("plain-private-key", cfg.get("privateKeyPem"));
        assertFalse(cfg.containsKey("password"));
    }

    @Test
    void update_preservesExistingSecretWhenNewConfigIsMasked() throws Exception {
        String encryptedOld = cryptoService.encryptIfNeeded("old-pass");
        ExternalConnection existing = new ExternalConnection();
        existing.setId(10L);
        existing.setType("SMTP");
        existing.setConfig(objectMapper.writeValueAsString(Map.of(
                "host", "old.example.com",
                "password", encryptedOld
        )));
        when(mapper.selectById(10L)).thenReturn(existing);

        ExternalConnection incoming = new ExternalConnection();
        incoming.setConfig("""
                {"host":"new.example.com","password":"old-****"}
                """);

        service.update(10L, incoming);

        ArgumentCaptor<ExternalConnection> captor = ArgumentCaptor.forClass(ExternalConnection.class);
        verify(mapper).updateById(captor.capture());
        Map<String, String> updated = service.parseConfig(captor.getValue().getConfig());
        assertEquals("new.example.com", updated.get("host"));
        assertEquals("old-pass", updated.get("password"));
    }

    @Test
    void update_samlBearerRemovesStaleBasicPassword() throws Exception {
        ExternalConnection existing = new ExternalConnection();
        existing.setId(15L);
        existing.setType("SuccessFactors");
        existing.setConfig(objectMapper.writeValueAsString(Map.of(
                "authType", "saml_bearer",
                "apiBaseUrl", "https://api.example.com/odata/v2",
                "apiKey", "oauth-client-id",
                "privateKeyPem", cryptoService.encryptIfNeeded("private-key"),
                "password", cryptoService.encryptIfNeeded("stale-password")
        )));
        when(mapper.selectById(15L)).thenReturn(existing);

        ExternalConnection incoming = new ExternalConnection();
        incoming.setConfig("""
                {"authType":"saml_bearer","apiBaseUrl":"https://api.example.com/odata/v2",
                 "apiKey":"oaut****","privateKeyPem":"----...(已配置)","password":"****"}
                """);

        service.update(15L, incoming);

        ArgumentCaptor<ExternalConnection> captor = ArgumentCaptor.forClass(ExternalConnection.class);
        verify(mapper).updateById(captor.capture());
        Map<String, Object> stored = objectMapper.readValue(captor.getValue().getConfig(), new TypeReference<>() {});
        assertFalse(stored.containsKey("password"));
        assertTrue(stored.containsKey("apiKey"));
        assertTrue(stored.containsKey("privateKeyPem"));
    }

    @Test
    void listReturnsMaskedConfigResponse() throws Exception {
        ExternalConnection conn = new ExternalConnection();
        conn.setId(11L);
        conn.setType("SMTP");
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "host", "smtp.example.com",
                "password", cryptoService.encryptIfNeeded("old-pass")
        )));
        when(mapper.selectList(any())).thenReturn(List.of(conn));

        List<ExternalConnectionResponse> responses = service.list("SMTP");

        Map<String, Object> masked = objectMapper.readValue(responses.get(0).getConfig(), new TypeReference<>() {});
        assertEquals("smtp.example.com", masked.get("host"));
        assertEquals("old-****", masked.get("password"));
    }

    @Test
    void listMasksUndecryptableSecretWithoutDroppingOtherConfigFields() throws Exception {
        SensitiveDataCryptoService otherKeyCrypto = new SensitiveDataCryptoService("other-key");
        ExternalConnection conn = new ExternalConnection();
        conn.setId(13L);
        conn.setType("SMTP");
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "host", "smtp.example.com",
                "port", "587",
                "password", otherKeyCrypto.encryptIfNeeded("old-pass")
        )));
        when(mapper.selectList(any())).thenReturn(List.of(conn));

        List<ExternalConnectionResponse> responses = service.list("SMTP");

        Map<String, Object> masked = objectMapper.readValue(responses.get(0).getConfig(), new TypeReference<>() {});
        assertEquals("smtp.example.com", masked.get("host"));
        assertEquals("587", masked.get("port"));
        assertEquals("****", masked.get("password"));
        assertThrows(BizException.class, () -> service.parseConfig(conn.getConfig()));
    }

    @Test
    void activeConnectionOptionConfigSkipsUndecryptableSecretWithoutDroppingOtherFields() throws Exception {
        SensitiveDataCryptoService otherKeyCrypto = new SensitiveDataCryptoService("other-key");
        ExternalConnection conn = new ExternalConnection();
        conn.setId(14L);
        conn.setType("SMTP");
        conn.setName("Default SMTP");
        conn.setIsActive(1);
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "host", "smtp.example.com",
                "port", "465",
                "username", "sender@example.com",
                "password", otherKeyCrypto.encryptIfNeeded("old-pass"),
                "fromAddress", "sender@example.com",
                "fromName", "Recognition Platform"
        )));
        when(mapper.selectOne(any())).thenReturn(conn);

        ExternalConnectionService.ConnectionConfig optionConfig = service.getActiveConnectionOptionConfig("SMTP");

        assertEquals("smtp.example.com", optionConfig.config().get("host"));
        assertEquals("465", optionConfig.config().get("port"));
        assertEquals("sender@example.com", optionConfig.config().get("username"));
        assertEquals("Recognition Platform", optionConfig.config().get("fromName"));
        assertFalse(optionConfig.config().containsKey("password"));
    }

    @Test
    void getByIdReturnsMaskedConfigResponse() {
        ExternalConnection conn = new ExternalConnection();
        conn.setId(12L);
        conn.setType("SMTP");
        conn.setConfig("""
                {"host":"smtp.example.com","password":"old-pass"}
                """);
        when(mapper.selectById(12L)).thenReturn(conn);

        ExternalConnectionResponse response = service.getById(12L);

        assertTrue(response.getConfig().contains("old-****"));
        assertFalse(response.getConfig().contains("old-pass"));
    }

    @Test
    void testConnection_hrdcOauth2FetchesTokenAndCallsTestPath() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer();
        server.createContext("/oauth/token", exchange -> respond(exchange, 200, "{\"access_token\":\"hrdc-token\"}"));
        server.createContext("/hrdc/ping", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"status\":\"S\"}");
        });

        ExternalConnection conn = new ExternalConnection();
        conn.setId(20L);
        conn.setType("HRDC");
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "baseUrl", serverBaseUrl() + "/hrdc",
                "testPath", "/ping",
                "authType", "oauth2",
                "tokenUrl", serverBaseUrl() + "/oauth/token",
                "grantType", "password",
                "clientId", "client-1",
                "clientSecret", "secret-1",
                "username", "user-1",
                "password", "pass-1",
                "timeoutMs", "5000"
        )));
        when(mapper.selectById(20L)).thenReturn(conn);

        Map<String, Object> result = service.testConnection(20L);

        assertEquals(true, result.get("success"));
        assertEquals("Bearer hrdc-token", authorization.get());
    }

    @Test
    void testConnection_successFactorsSamlBearerUsesIdpThenTokenThenMetadata() throws Exception {
        AtomicReference<Map<String, String>> assertionRequest = new AtomicReference<>();
        AtomicReference<Map<String, String>> tokenRequest = new AtomicReference<>();
        AtomicReference<String> metadataAuthorization = new AtomicReference<>();
        startServer();
        server.createContext("/oauth/idp", exchange -> {
            assertionRequest.set(readRequestForm(exchange));
            respond(exchange, 200, "assertion-value");
        });
        server.createContext("/oauth/token", exchange -> {
            tokenRequest.set(readRequestForm(exchange));
            respond(exchange, 200, "{\"access_token\":\"sf-token\"}");
        });
        server.createContext("/odata/v2/$metadata", exchange -> {
            metadataAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "<edmx:Edmx/>");
        });

        ExternalConnection conn = new ExternalConnection();
        conn.setId(24L);
        conn.setType("SuccessFactors");
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "apiBaseUrl", serverBaseUrl() + "/odata/v2",
                "authType", "saml_bearer",
                "tokenUrl", serverBaseUrl() + "/oauth/token",
                "apiKey", "sf-client-1",
                "companyId", "company-1",
                "username", "api-user-1",
                "privateKeyPem", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nabcDEF+/=\n-----END ENCRYPTED PRIVATE KEY-----"
        )));
        when(mapper.selectById(24L)).thenReturn(conn);

        Map<String, Object> result = service.testConnection(24L);

        assertEquals(true, result.get("success"));
        assertEquals("sf-client-1", assertionRequest.get().get("client_id"));
        assertEquals("company-1", assertionRequest.get().get("company_id"));
        assertEquals("api-user-1", assertionRequest.get().get("user_id"));
        assertEquals(serverBaseUrl() + "/oauth/token", assertionRequest.get().get("token_url"));
        assertEquals("abcDEF+/=", assertionRequest.get().get("private_key"));
        assertEquals("assertion-value", tokenRequest.get().get("assertion"));
        assertEquals("sf-client-1", tokenRequest.get().get("client_id"));
        assertEquals("company-1", tokenRequest.get().get("company_id"));
        assertEquals("Bearer sf-token", metadataAuthorization.get());
    }

    @Test
    void testConnection_iasWithAuthCodeFetchesTokenAndUserInfo() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer();
        server.createContext("/oauth2/token", exchange -> respond(exchange, 200, "{\"access_token\":\"ias-token\"}"));
        server.createContext("/oauth2/userinfo", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"sub\":\"ias-user-1\",\"email\":\"user@example.com\"}");
        });

        ExternalConnection conn = new ExternalConnection();
        conn.setId(21L);
        conn.setType("IAS");
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "authorizeUrl", serverBaseUrl() + "/oauth2/authorize?client_id={client_id}&redirect_uri={redirect_uri}",
                "tokenUrl", serverBaseUrl() + "/oauth2/token",
                "userInfoUrl", serverBaseUrl() + "/oauth2/userinfo",
                "clientId", "client-1",
                "clientSecret", "secret-1",
                "redirectUri", "http://localhost:8080/api/v1/auth/ias/oauth",
                "testAuthCode", "code-1"
        )));
        when(mapper.selectById(21L)).thenReturn(conn);

        Map<String, Object> result = service.testConnection(21L);

        assertEquals(true, result.get("success"));
        assertEquals("Bearer ias-token", authorization.get());
    }

    @Test
    void buildActiveIasAuthorizeUrl_derivesRedirectUriFromAppBaseUrl() throws Exception {
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://rp.example.com/recongition");

        ExternalConnection conn = new ExternalConnection();
        conn.setId(22L);
        conn.setType("IAS");
        conn.setIsActive(1);
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "tenantBaseUrl", "https://ias.example.com",
                "clientId", "client-1"
        )));
        when(mapper.selectOne(any())).thenReturn(conn);

        String authorizeUrl = service.buildActiveIasAuthorizeUrl();

        assertTrue(authorizeUrl.startsWith("https://ias.example.com/oauth2/authorize?"));
        assertTrue(authorizeUrl.contains("client_id=client-1"));
        assertTrue(authorizeUrl.contains("redirect_uri=https%3A%2F%2Frp.example.com%2Frecongition%2Fapi%2Fv1%2Fauth%2Fias%2Foauth"));
    }

    @Test
    void resolveActiveIasUserProfileByCode_derivesTokenAndUserInfoUrls() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ExternalConnectionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AtomicReference<String> authorization = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://rp.example.com/recongition");
        startServer();
        server.createContext("/oauth2/token", exchange -> respond(exchange, 200, "access_token=ias-token&token_type=Bearer"));
        server.createContext("/oauth2/userinfo", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"sub\":\"ias-user-1\",\"mail\":\"user@example.com\"}");
        });

        ExternalConnection conn = new ExternalConnection();
        conn.setId(23L);
        conn.setType("IAS");
        conn.setIsActive(1);
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "tenantBaseUrl", serverBaseUrl(),
                "clientId", "client-1",
                "clientSecret", "secret-1"
        )));
        when(mapper.selectOne(any())).thenReturn(conn);

        try {
            ExternalConnectionService.IasUserProfile profile = service.resolveActiveIasUserProfileByCode("code-1");

            assertEquals("ias-user-1", profile.employeeId());
            assertEquals("user@example.com", profile.email());
            assertEquals("ias-token", profile.accessToken());
            assertEquals("Bearer ias-token", authorization.get());
            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("employeeId(sub)=ias-user-1")
                            && message.contains("email=us***@example.com")
                            && message.contains("claimKeys=[mail, sub]")));
            assertFalse(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("ias-token")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void resolveActiveIasUserProfileByCode_rejectsUserInfoWithoutEmployeeIdSubject() throws Exception {
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://rp.example.com/recongition");
        startServer();
        server.createContext("/oauth2/token", exchange ->
                respond(exchange, 200, "access_token=ias-token&token_type=Bearer"));
        server.createContext("/oauth2/userinfo", exchange ->
                respond(exchange, 200, "{\"username\":\"10001234\",\"mail\":\"user@example.com\"}"));

        ExternalConnection conn = new ExternalConnection();
        conn.setId(24L);
        conn.setType("IAS");
        conn.setIsActive(1);
        conn.setConfig(objectMapper.writeValueAsString(Map.of(
                "tenantBaseUrl", serverBaseUrl(),
                "clientId", "client-1",
                "clientSecret", "secret-1"
        )));
        when(mapper.selectOne(any())).thenReturn(conn);

        BizException error = assertThrows(
                BizException.class,
                () -> service.resolveActiveIasUserProfileByCode("code-1"));

        assertEquals("IAS userinfo 未返回工号（sub）", error.getMessage());
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, String> readRequestForm(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
