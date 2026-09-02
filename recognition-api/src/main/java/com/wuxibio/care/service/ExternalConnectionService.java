package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.ExternalConnectionResponse;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ExternalConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ExternalConnectionService.class);
    private final ExternalConnectionMapper mapper;
    private final SensitiveDataCryptoService cryptoService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final Set<String> NORMALIZED_SENSITIVE_KEYS = Set.of(
            "password", "appsecret", "clientsecret", "bindpassword", "secret", "privatekeypem",
            "privatekey", "secretkey", "accesskeysecret", "apikey", "apikeyvalue", "testauthcode"
    );

    private static final Set<String> HRDC_AUTH_TYPES = Set.of("none", "api_key", "oauth2", "basic");
    private static final String DEFAULT_IAS_FRONTEND_SUCCESS_PATH = "/sso/callback";
    private static final String DEFAULT_IAS_FRONTEND_ERROR_PATH = "/login";

    @Value("${app.base-url:}")
    private String appBaseUrl;

    public record IasUserProfile(String employeeId, String email, String accessToken) {}

    public record ConnectionConfig(
            Long id,
            String type,
            String name,
            String status,
            Integer isActive,
            LocalDateTime lastTestedAt,
            String lastTestResult,
            Map<String, String> config) {
    }

    private record IasConnectionConfig(
            String authorizeUrl,
            String tokenUrl,
            String userInfoUrl,
            String clientId,
            String clientSecret,
            String redirectUri
    ) {}

    public ExternalConnectionService(ExternalConnectionMapper mapper, SensitiveDataCryptoService cryptoService) {
        this.mapper = mapper;
        this.cryptoService = cryptoService;
    }

    public List<ExternalConnectionResponse> list(String type) {
        LambdaQueryWrapper<ExternalConnection> w = new LambdaQueryWrapper<>();
        if (type != null && !type.isBlank()) w.eq(ExternalConnection::getType, type);
        w.orderByDesc(ExternalConnection::getIsActive).orderByDesc(ExternalConnection::getUpdatedAt);
        return mapper.selectList(w).stream().map(this::toResponse).toList();
    }

    public ExternalConnectionResponse getById(Long id) {
        ExternalConnection conn = mapper.selectById(id);
        if (conn == null) throw new BizException("连接不存在");
        return toResponse(conn);
    }

    @Transactional
    public ExternalConnectionResponse create(ExternalConnection conn) {
        if (conn.getName() == null || conn.getName().isBlank()) throw new BizException("名称不能为空");
        if (conn.getType() == null || conn.getType().isBlank()) throw new BizException("类型不能为空");
        conn.setCreatedBy(SecurityUtil.getCurrentUserId());
        if (conn.getIsActive() == null) conn.setIsActive(0);
        if (conn.getStatus() == null) conn.setStatus("Active");
        if (conn.getConfig() != null) {
            conn.setConfig(encryptConfig(conn.getConfig()));
        }
        mapper.insert(conn);
        if (conn.getIsActive() == 1) {
            deactivateOthers(conn.getType(), conn.getId());
        }
        return toResponse(conn);
    }

    @Transactional
    public void update(Long id, ExternalConnection conn) {
        ExternalConnection existing = mapper.selectById(id);
        if (existing == null) throw new BizException("连接不存在");

        ExternalConnection update = new ExternalConnection();
        update.setId(id);
        if (conn.getName() != null) update.setName(conn.getName());
        if (conn.getEnvironment() != null) update.setEnvironment(conn.getEnvironment());
        if (conn.getStatus() != null) update.setStatus(conn.getStatus());
        if (conn.getConfig() != null) {
            update.setConfig(encryptConfig(mergeConfig(existing.getConfig(), conn.getConfig())));
        }
        mapper.updateById(update);

        if (conn.getIsActive() != null && conn.getIsActive() == 1) {
            deactivateOthers(existing.getType(), id);
            ExternalConnection activeUpdate = new ExternalConnection();
            activeUpdate.setId(id);
            activeUpdate.setIsActive(1);
            mapper.updateById(activeUpdate);
        }
    }

    @Transactional
    public void setActive(Long id) {
        ExternalConnection conn = mapper.selectById(id);
        if (conn == null) throw new BizException("连接不存在");
        deactivateOthers(conn.getType(), id);
        ExternalConnection update = new ExternalConnection();
        update.setId(id);
        update.setIsActive(1);
        mapper.updateById(update);
    }

    @Transactional
    public void delete(Long id) {
        ExternalConnection conn = mapper.selectById(id);
        if (conn == null) throw new BizException("连接不存在");
        mapper.deleteById(id);
    }

    public Map<String, Object> testConnection(Long id) {
        ExternalConnection conn = mapper.selectById(id);
        if (conn == null) throw new BizException("连接不存在");

        Map<String, Object> result = new LinkedHashMap<>();
        String testResult;
        String message;

        try {
            Map<String, String> cfg = parseConfig(conn.getConfig());
            switch (conn.getType()) {
                case "DingTalk" -> message = testDingTalk(cfg);
                case "SMTP" -> message = testSmtp(cfg);
                case "SuccessFactors" -> message = testSuccessFactors(cfg);
                case "HRDC" -> message = testHrdc(cfg);
                case "IAS" -> message = testIas(cfg);
                default -> message = "不支持的连接类型测试";
            }
            testResult = "Success";
            result.put("success", true);
            result.put("message", message);
        } catch (Exception e) {
            testResult = "Failed";
            result.put("success", false);
            result.put("message", e.getMessage());
            log.error("Connection test failed for id={}: {}", id, e.getMessage());
        }

        ExternalConnection update = new ExternalConnection();
        update.setId(id);
        update.setLastTestedAt(LocalDateTime.now());
        update.setLastTestResult(testResult);
        mapper.updateById(update);

        result.put("testedAt", LocalDateTime.now().toString());
        return result;
    }

    public Map<String, String> getActiveConfig(String type) {
        ConnectionConfig active = getActiveConnectionConfig(type);
        return active == null ? null : active.config();
    }

    public ConnectionConfig getActiveConnectionConfig(String type) {
        ExternalConnection conn = mapper.selectOne(
                new LambdaQueryWrapper<ExternalConnection>()
                        .eq(ExternalConnection::getType, type)
                        .eq(ExternalConnection::getIsActive, 1));
        if (conn == null) return null;
        return toConnectionConfig(conn);
    }

    public ConnectionConfig getActiveConnectionOptionConfig(String type) {
        ExternalConnection conn = mapper.selectOne(
                new LambdaQueryWrapper<ExternalConnection>()
                        .eq(ExternalConnection::getType, type)
                        .eq(ExternalConnection::getIsActive, 1));
        if (conn == null) return null;
        return toConnectionOptionConfig(conn);
    }

    public ConnectionConfig getConnectionConfig(Long id, String expectedType) {
        if (id == null) {
            throw new BizException("连接 ID 不能为空");
        }
        ExternalConnection conn = mapper.selectById(id);
        if (conn == null) {
            throw new BizException("连接不存在");
        }
        if (expectedType != null && !expectedType.isBlank() && !expectedType.equals(conn.getType())) {
            throw new BizException("连接类型不匹配，期望 " + expectedType + "，实际 " + conn.getType());
        }
        return toConnectionConfig(conn);
    }

    public String testSmtpConfig(Map<String, String> cfg) {
        return testSmtp(cfg);
    }

    public String buildActiveIasAuthorizeUrl() {
        IasConnectionConfig cfg = resolveIasConnectionConfig(requireActiveIasConfig(), false);
        return buildIasAuthorizeUrl(cfg.authorizeUrl(), cfg.clientId(), cfg.redirectUri());
    }

    public IasUserProfile resolveActiveIasUserProfileByCode(String code) throws Exception {
        String normalizedCode = safeTrim(code);
        if (normalizedCode.isBlank()) {
            throw new BizException("IAS 授权码不能为空");
        }

        IasConnectionConfig cfg = resolveIasConnectionConfig(requireActiveIasConfig(), true);
        String accessToken = fetchIasAccessToken(
                cfg.tokenUrl(),
                cfg.clientId(),
                cfg.clientSecret(),
                cfg.redirectUri(),
                normalizedCode);
        return fetchIasUserProfile(cfg.userInfoUrl(), accessToken);
    }

    public String buildIasSuccessRedirect(String token) {
        Map<String, String> cfg = Optional.ofNullable(getActiveConfig("IAS")).orElse(Map.of());
        String target = firstNonBlank(
                cfg.get("frontendSuccessUrl"),
                cfg.get("frontend_success_url"),
                defaultIasFrontendSuccessUrl());
        return UriComponentsBuilder.fromUriString(target)
                .queryParam("token", token)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
    }

    public String buildIasErrorRedirect(String message) {
        Map<String, String> cfg = Optional.ofNullable(getActiveConfig("IAS")).orElse(Map.of());
        String target = firstNonBlank(
                cfg.get("frontendErrorUrl"),
                cfg.get("frontend_error_url"),
                defaultIasFrontendErrorUrl());
        return UriComponentsBuilder.fromUriString(target)
                .queryParam("error", sanitizeIasRedirectMessage(message))
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
    }

    /**
     * Generate a self-signed X.509 certificate + RSA private key for SAML Bearer auth.
     * Uses OpenSSL via ProcessBuilder.
     */
    public Map<String, String> generateSamlCertificate(String commonName, Integer validDays) {
        if (commonName == null || commonName.isBlank()) commonName = "WuXiBio-Care";
        if (validDays == null || validDays <= 0) validDays = 365;

        try {
            Path tempDir = Files.createTempDirectory("sf-saml-");
            Path configPath = tempDir.resolve("openssl.cnf");
            Path keyPath = tempDir.resolve("private.pem");
            Path certPath = tempDir.resolve("public.pem");

            String opensslConfig = """
                    [ req ]
                    default_bits = 2048
                    prompt = no
                    default_md = sha256
                    distinguished_name = dn
                    x509_extensions = v3_req

                    [ dn ]
                    CN = %s

                    [ v3_req ]
                    subjectKeyIdentifier = hash
                    authorityKeyIdentifier = keyid,issuer
                    basicConstraints = critical,CA:FALSE
                    keyUsage = critical,digitalSignature,keyEncipherment
                    """.formatted(commonName);

            Files.writeString(configPath, opensslConfig);

            ProcessBuilder pb = new ProcessBuilder(
                    "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256",
                    "-keyout", keyPath.toString(),
                    "-out", certPath.toString(),
                    "-days", String.valueOf(validDays),
                    "-nodes",
                    "-config", configPath.toString(),
                    "-extensions", "v3_req"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new BizException("OpenSSL 执行失败: " + output);
            }

            String privateKeyPem = Files.readString(keyPath);
            String certificatePem = Files.readString(certPath);
            String certificateUploadContent = certificatePem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s+", "");

            // Cleanup
            Files.deleteIfExists(configPath);
            Files.deleteIfExists(keyPath);
            Files.deleteIfExists(certPath);
            Files.deleteIfExists(tempDir);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("privateKeyPem", privateKeyPem);
            result.put("certificatePem", certificatePem);
            result.put("certificateUploadContent", certificateUploadContent);
            result.put("commonName", commonName);
            result.put("validDays", String.valueOf(validDays));
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("证书生成失败: " + e.getMessage());
        }
    }

    // ===== Connection test implementations =====

    private String testDingTalk(Map<String, String> cfg) throws Exception {
        String appKey = cfg.get("appKey");
        String appSecret = cfg.get("appSecret");
        if (appKey == null || appSecret == null) throw new BizException("缺少 appKey 或 appSecret");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://oapi.dingtalk.com/gettoken?appkey=" + appKey + "&appsecret=" + appSecret))
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        int errcode = ((Number) body.getOrDefault("errcode", -1)).intValue();
        if (errcode != 0) {
            throw new BizException("钉钉认证失败: " + body.get("errmsg"));
        }
        String accessToken = (String) body.get("access_token");
        return "认证成功，access_token: " + accessToken.substring(0, 8) + "...";
    }

    private String testSmtp(Map<String, String> cfg) {
        String host = cfg.get("host");
        String port = cfg.get("port");
        String username = cfg.get("username");
        String password = cfg.get("password");
        boolean useSsl = "true".equalsIgnoreCase(cfg.getOrDefault("useSsl", "true"));
        if (host == null || host.isBlank()) throw new BizException("缺少 SMTP host");

        try {
            var props = new java.util.Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port != null ? port : "465");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.timeout", "8000");
            props.put("mail.smtp.connectiontimeout", "5000");
            if (useSsl) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }

            var session = jakarta.mail.Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(username, password);
                }
            });
            var transport = session.getTransport("smtp");
            transport.connect();
            transport.close();
            return "SMTP 认证成功: " + username + " @ " + host + ":" + port;
        } catch (Exception e) {
            throw new BizException("SMTP 认证失败: " + e.getMessage());
        }
    }

    private String testSuccessFactors(Map<String, String> cfg) throws Exception {
        String authType = cfg.getOrDefault("authType", "basic");
        String apiBaseUrl = cfg.get("apiBaseUrl");
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) throw new BizException("缺少 API Base URL");

        // Build Authorization header based on auth type
        String authHeader;
        switch (authType) {
            case "basic" -> authHeader = buildSFBasicAuth(cfg);
            case "oauth2" -> authHeader = "Bearer " + fetchSFOAuth2Token(cfg);
            case "saml_bearer" -> authHeader = "Bearer " + fetchSFSamlBearerToken(cfg);
            default -> throw new BizException("不支持的认证方式: " + authType);
        }

        // Test: fetch $metadata with auth
        String metadataUrl = normalizeBaseUrl(apiBaseUrl) + "/odata/v2/$metadata";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(metadataUrl))
                .header("Accept", "application/xml")
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(15))
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return "SF OData API 认证成功 (" + authType + ")，$metadata 可访问";
        } else if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new BizException("认证失败 (HTTP " + response.statusCode() + ")，请检查凭据");
        } else {
            throw new BizException("SF API 响应异常: HTTP " + response.statusCode());
        }
    }

    private String testHrdc(Map<String, String> cfg) throws Exception {
        String baseUrl = safeTrim(cfg.get("baseUrl"));
        if (baseUrl.isBlank()) throw new BizException("缺少 HRDC Base URL");

        String authType = normalizeHrdcAuthType(cfg.get("authType"));
        String testUrl = buildExternalUrl(baseUrl, cfg.get("testPath"));
        String method = safeTrim(firstNonBlank(cfg.get("httpMethod"), "GET")).toUpperCase(Locale.ROOT);
        int timeoutMs = parseTimeoutMs(cfg.get("timeoutMs"));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(testUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");
        applyHrdcAuth(builder, cfg, authType, timeoutMs);

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(firstNonBlank(cfg.get("testBody"), "{}")));
        } else if ("GET".equals(method)) {
            builder.GET();
        } else {
            throw new BizException("HRDC 测试方法仅支持 GET/POST");
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return "HRDC 连接成功: HTTP " + response.statusCode();
        }
        throw new BizException("HRDC 连接失败: HTTP " + response.statusCode() + " - " + abbreviate(response.body(), 240));
    }

    private void applyHrdcAuth(
            HttpRequest.Builder builder,
            Map<String, String> cfg,
            String authType,
            int timeoutMs) throws Exception {
        Map<String, String> headers = buildHrdcAuthHeaders(cfg, authType, timeoutMs);
        headers.forEach(builder::header);
    }

    public Map<String, String> buildHrdcAuthHeaders(Map<String, String> cfg) {
        try {
            return buildHrdcAuthHeaders(cfg, normalizeHrdcAuthType(cfg.get("authType")), parseTimeoutMs(cfg.get("timeoutMs")));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("构建 HRDC 鉴权失败: " + e.getMessage());
        }
    }

    private Map<String, String> buildHrdcAuthHeaders(
            Map<String, String> cfg,
            String authType,
            int timeoutMs) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        switch (authType) {
            case "none" -> {
            }
            case "api_key" -> {
                String headerName = firstNonBlank(cfg.get("apiKeyHeader"), cfg.get("headerName"), "X-API-Key");
                String apiKey = firstNonBlank(cfg.get("apiKey"), cfg.get("api_key"), cfg.get("apiKeyValue"));
                if (safeTrim(apiKey).isBlank()) throw new BizException("HRDC API Key 鉴权缺少 apiKey");
                headers.put(headerName, apiKey);
            }
            case "basic" -> {
                String username = safeTrim(cfg.get("username"));
                String password = safeTrim(cfg.get("password"));
                if (username.isBlank() || password.isBlank()) {
                    throw new BizException("HRDC Basic 鉴权缺少 username/password");
                }
                String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + token);
            }
            case "oauth2" -> headers.put("Authorization", "Bearer " + fetchHrdcOAuth2Token(cfg, timeoutMs));
            default -> throw new BizException("不支持的 HRDC 鉴权方式: " + authType);
        }
        return headers;
    }

    private String fetchHrdcOAuth2Token(Map<String, String> cfg, int timeoutMs) throws Exception {
        String tokenUrl = safeTrim(firstNonBlank(cfg.get("tokenUrl"), cfg.get("token_url")));
        if (tokenUrl.isBlank()) throw new BizException("获取 HRDC token 失败: tokenUrl 未配置");

        String grantType = firstNonBlank(cfg.get("grantType"), cfg.get("grant_type"), "client_credentials");
        String clientId = safeTrim(firstNonBlank(cfg.get("clientId"), cfg.get("client_id")));
        String clientSecret = safeTrim(firstNonBlank(cfg.get("clientSecret"), cfg.get("client_secret")));
        boolean clientAuthBasic = parseBoolean(firstNonBlank(cfg.get("clientAuthBasic"), cfg.get("client_auth_basic")));

        StringBuilder body = new StringBuilder();
        appendForm(body, "grant_type", grantType);
        if ("password".equalsIgnoreCase(grantType)) {
            appendForm(body, "username", firstNonBlank(cfg.get("username"), ""));
            appendForm(body, "password", firstNonBlank(cfg.get("password"), ""));
        }
        if (!clientAuthBasic) {
            if (!clientId.isBlank()) appendForm(body, "client_id", clientId);
            if (!clientSecret.isBlank()) appendForm(body, "client_secret", clientSecret);
        }
        String scope = safeTrim(cfg.get("scope"));
        if (!scope.isBlank()) appendForm(body, "scope", scope);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (clientAuthBasic && !clientId.isBlank()) {
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> response = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("获取 HRDC token 失败: HTTP " + response.statusCode());
        }
        Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
        String tokenField = firstNonBlank(cfg.get("tokenField"), cfg.get("token_field"), "access_token");
        String accessToken = payload.get(tokenField) == null ? "" : String.valueOf(payload.get(tokenField)).trim();
        if (accessToken.isBlank()) throw new BizException("获取 HRDC token 失败: 响应缺少 " + tokenField);
        return accessToken;
    }

    private String testIas(Map<String, String> cfg) throws Exception {
        IasConnectionConfig iasCfg = resolveIasConnectionConfig(cfg, false);

        String builtAuthorizeUrl = buildIasAuthorizeUrl(iasCfg.authorizeUrl(), iasCfg.clientId(), iasCfg.redirectUri());
        String testAuthCode = safeTrim(cfg.get("testAuthCode"));
        if (testAuthCode.isBlank()) {
            return "IAS 授权 URL 生成成功，请通过 SSO 登录闭环验证 token/userinfo: " + abbreviate(builtAuthorizeUrl, 160);
        }

        iasCfg = resolveIasConnectionConfig(cfg, true);
        String accessToken = fetchIasAccessToken(
                iasCfg.tokenUrl(),
                iasCfg.clientId(),
                iasCfg.clientSecret(),
                iasCfg.redirectUri(),
                testAuthCode);
        String employeeId = fetchIasUserProfile(iasCfg.userInfoUrl(), accessToken).employeeId();
        return "IAS token/userinfo 验证成功: " + employeeId;
    }

    private String buildIasAuthorizeUrl(String authorizeUrl, String clientId, String redirectUri) {
        if (authorizeUrl.contains("{client_id}") || authorizeUrl.contains("{redirect_uri}")) {
            return authorizeUrl
                    .replace("{client_id}", URLEncoder.encode(clientId, StandardCharsets.UTF_8))
                    .replace("{redirect_uri}", URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
        }
        String separator = authorizeUrl.contains("?") ? "&" : "?";
        return authorizeUrl + separator
                + "response_type=code"
                + "&scope=" + URLEncoder.encode("openid email", StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&state=admin_sso"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
    }

    private String fetchIasAccessToken(
            String tokenUrl,
            String clientId,
            String clientSecret,
            String redirectUri,
            String code) throws Exception {
        StringBuilder body = new StringBuilder();
        appendForm(body, "grant_type", "authorization_code");
        appendForm(body, "client_id", clientId);
        appendForm(body, "client_secret", clientSecret);
        appendForm(body, "redirect_uri", redirectUri);
        appendForm(body, "scope", "openid");
        appendForm(body, "code", code);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("IAS token 接口调用失败: HTTP " + response.statusCode() + " - " + abbreviate(response.body(), 240));
        }

        String raw = safeTrim(response.body());
        String accessToken = "";
        try {
            Map<String, Object> payload = objectMapper.readValue(raw, new TypeReference<>() {});
            accessToken = payload.get("access_token") == null ? "" : String.valueOf(payload.get("access_token")).trim();
        } catch (Exception ignored) {
            Map<String, String> formBody = parseFormBody(raw);
            accessToken = firstNonBlank(formBody.get("access_token"), formBody.get("token"));
        }
        if (accessToken.isBlank()) throw new BizException("IAS token 获取失败: 未返回 access_token");
        return accessToken;
    }

    private IasUserProfile fetchIasUserProfile(String userInfoUrl, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(userInfoUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("IAS userinfo 接口调用失败: HTTP " + response.statusCode() + " - " + abbreviate(response.body(), 240));
        }
        Map<String, Object> payload = objectMapper.readValue(response.body(), new TypeReference<>() {});
        String employeeId = payload.get("sub") == null ? "" : String.valueOf(payload.get("sub"));
        String email = firstNonBlank(
                payload.get("mail") == null ? "" : String.valueOf(payload.get("mail")),
                payload.get("email") == null ? "" : String.valueOf(payload.get("email")));
        log.info("IAS userinfo received before local account binding: employeeId(sub)={}, email={}, claimKeys={}",
                singleLineForLog(employeeId),
                maskEmailForLog(email),
                payload.keySet().stream().map(this::singleLineForLog).sorted().toList());
        if (safeTrim(employeeId).isBlank()) throw new BizException("IAS userinfo 未返回工号（sub）");
        return new IasUserProfile(safeTrim(employeeId), safeTrim(email), accessToken);
    }

    // ===== SF Auth Helpers =====

    /**
     * Build SF Authorization header from connection config.
     * Public so OdataService can reuse.
     */
    public String buildSFAuthHeader(Map<String, String> cfg) {
        String authType = cfg.getOrDefault("authType", "basic");
        try {
            return switch (authType) {
                case "basic" -> buildSFBasicAuth(cfg);
                case "oauth2" -> "Bearer " + fetchSFOAuth2Token(cfg);
                case "saml_bearer" -> "Bearer " + fetchSFSamlBearerToken(cfg);
                default -> throw new BizException("不支持的认证方式: " + authType);
            };
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("构建 SF 认证失败: " + e.getMessage());
        }
    }

    private String buildSFBasicAuth(Map<String, String> cfg) {
        String companyId = cfg.get("companyId");
        String username = cfg.get("username");
        String password = cfg.get("password");
        if (companyId == null || username == null || password == null) {
            throw new BizException("Basic Auth 需要 companyId, username, password");
        }
        String credentials = username + "@" + companyId + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String fetchSFOAuth2Token(Map<String, String> cfg) throws Exception {
        String tokenUrl = cfg.get("tokenUrl");
        String clientId = cfg.get("clientId");
        String clientSecret = cfg.get("clientSecret");
        String companyId = cfg.get("companyId");
        String username = cfg.get("username");
        String password = cfg.get("password");

        if (tokenUrl == null || clientId == null) {
            throw new BizException("OAuth2 需要 tokenUrl 和 clientId");
        }

        // SF OAuth2 supports two sub-flows:
        // 1. client_credentials (if client_secret provided)
        // 2. password grant (if username+password provided, more common in SF)
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));

        if (clientSecret != null && !clientSecret.isBlank()) {
            bodyBuilder.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }

        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            // Password grant - more widely supported on SF
            bodyBuilder.append("&grant_type=password");
            String fullUsername = (companyId != null && !companyId.isBlank())
                    ? username + "@" + companyId : username;
            bodyBuilder.append("&username=").append(URLEncoder.encode(fullUsername, StandardCharsets.UTF_8));
            bodyBuilder.append("&password=").append(URLEncoder.encode(password, StandardCharsets.UTF_8));
        } else {
            // Client credentials grant
            bodyBuilder.append("&grant_type=client_credentials");
        }

        if (companyId != null && !companyId.isBlank()) {
            bodyBuilder.append("&company_id=").append(URLEncoder.encode(companyId, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyBuilder.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
        if (result.containsKey("access_token")) {
            return (String) result.get("access_token");
        }
        throw new BizException("OAuth2 token 获取失败: " + response.body());
    }

    private String fetchSFSamlBearerToken(Map<String, String> cfg) throws Exception {
        String tokenUrl = safeTrim(cfg.get("tokenUrl"));
        String assertionUrl = resolveSfAssertionUrl(cfg.get("assertionUrl"), tokenUrl);
        String apiKey = safeTrim(cfg.get("apiKey"));
        String username = safeTrim(cfg.get("username"));
        String companyId = safeTrim(cfg.get("companyId"));
        String privateKey = flattenSfPrivateKey(cfg.get("privateKeyPem"));

        if (tokenUrl.isBlank() || apiKey.isBlank() || username.isBlank()
                || companyId.isBlank() || privateKey.isBlank()) {
            throw new BizException("SF OAuth 需要 tokenUrl, apiKey, username, companyId, privateKeyPem");
        }

        String grantType = "urn:ietf:params:oauth:grant-type:saml2-bearer";

        // The configured SF tenant generates the assertion first. The response is then
        // exchanged for an OAuth access token in the second request.
        StringBuilder assertionBody = new StringBuilder();
        appendForm(assertionBody, "client_id", apiKey);
        appendForm(assertionBody, "company_id", companyId);
        appendForm(assertionBody, "user_id", username);
        appendForm(assertionBody, "token_url", tokenUrl);
        appendForm(assertionBody, "private_key", privateKey);
        appendForm(assertionBody, "grant_type", grantType);

        HttpRequest assertionRequest = HttpRequest.newBuilder()
                .uri(URI.create(assertionUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/plain, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(assertionBody.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> assertionResponse = httpClient.send(
                assertionRequest, HttpResponse.BodyHandlers.ofString());
        if (assertionResponse.statusCode() < 200 || assertionResponse.statusCode() >= 300) {
            throw new BizException("SAML assertion 获取失败: HTTP " + assertionResponse.statusCode()
                    + formatSfOAuthError(assertionResponse.body()));
        }
        String assertion = extractSfAssertion(assertionResponse.body());

        StringBuilder tokenBody = new StringBuilder();
        appendForm(tokenBody, "company_id", companyId);
        appendForm(tokenBody, "client_id", apiKey);
        appendForm(tokenBody, "grant_type", grantType);
        appendForm(tokenBody, "assertion", assertion);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BizException("SF token 获取失败: HTTP " + response.statusCode()
                    + formatSfOAuthError(response.body()));
        }
        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
        String accessToken = result.get("access_token") == null
                ? "" : safeTrim(String.valueOf(result.get("access_token")));
        if (accessToken.isBlank()) {
            throw new BizException("SF token 获取失败: 响应中没有 access_token");
        }
        return accessToken;
    }

    private String resolveSfAssertionUrl(String configuredUrl, String tokenUrl) {
        String configured = safeTrim(configuredUrl);
        if (!configured.isBlank()) {
            return configured;
        }
        if (tokenUrl.matches("(?i).*/oauth/token/?$")) {
            return tokenUrl.replaceFirst("(?i)/oauth/token/?$", "/oauth/idp");
        }
        throw new BizException("缺少 Assertion URL，且无法从 Token URL 自动得到 /oauth/idp 地址");
    }

    private String flattenSfPrivateKey(String pem) {
        String normalized = safeTrim(pem);
        if (normalized.isBlank()) {
            return "";
        }
        for (String type : List.of("ENCRYPTED PRIVATE KEY", "PRIVATE KEY", "RSA PRIVATE KEY")) {
            String begin = "-----BEGIN " + type + "-----";
            String end = "-----END " + type + "-----";
            int start = normalized.indexOf(begin);
            int finish = normalized.indexOf(end);
            if (start >= 0 && finish > start) {
                normalized = normalized.substring(start + begin.length(), finish);
                break;
            }
        }
        return normalized.replaceAll("\\s+", "");
    }

    private String extractSfAssertion(String rawBody) throws Exception {
        String raw = safeTrim(rawBody);
        if (raw.isBlank()) {
            throw new BizException("SAML assertion 获取失败: SF 未返回 assertion");
        }
        if (raw.startsWith("{") && raw.endsWith("}")) {
            Map<String, Object> payload = objectMapper.readValue(raw, new TypeReference<>() {});
            String assertion = firstNonBlank(
                    payload.get("assertion") == null ? "" : String.valueOf(payload.get("assertion")),
                    payload.get("saml_assertion") == null ? "" : String.valueOf(payload.get("saml_assertion")));
            if (assertion.isBlank()) {
                throw new BizException("SAML assertion 获取失败: SF 响应中没有 assertion");
            }
            return assertion;
        }
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return safeTrim(objectMapper.readValue(raw, String.class));
        }
        return raw;
    }

    private String formatSfOAuthError(String rawBody) {
        String raw = safeTrim(rawBody);
        if (raw.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(raw, new TypeReference<>() {});
            String detail = firstNonBlank(
                    payload.get("error_description") == null ? "" : String.valueOf(payload.get("error_description")),
                    payload.get("error") == null ? "" : String.valueOf(payload.get("error")));
            return detail.isBlank() ? "" : " - " + abbreviate(detail, 200);
        } catch (Exception ignored) {
            return " - " + abbreviate(raw.replaceAll("[\\r\\n\\t]+", " "), 200);
        }
    }

    private String buildSamlAssertion(String tokenUrl, String username, String companyId, String apiKey) {
        String assertionId = "_" + UUID.randomUUID();
        String sessionIndex = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC);
        String issueInstant = fmt.format(now);
        String notBefore = fmt.format(now.minusSeconds(300));
        String notOnOrAfter = fmt.format(now.plusSeconds(1800));

        // Audience = token URL
        String audience = tokenUrl;

        return """
                <saml2:Assertion xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion" ID="%s" IssueInstant="%s" Version="2.0">\
                <saml2:Issuer>www.successfactors.com</saml2:Issuer>\
                <saml2:Subject>\
                <saml2:NameID Format="urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified">%s</saml2:NameID>\
                <saml2:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">\
                <saml2:SubjectConfirmationData NotOnOrAfter="%s" Recipient="%s"/>\
                </saml2:SubjectConfirmation>\
                </saml2:Subject>\
                <saml2:Conditions NotBefore="%s" NotOnOrAfter="%s">\
                <saml2:AudienceRestriction>\
                <saml2:Audience>www.successfactors.com</saml2:Audience>\
                </saml2:AudienceRestriction>\
                </saml2:Conditions>\
                <saml2:AuthnStatement AuthnInstant="%s" SessionIndex="%s">\
                <saml2:AuthnContext>\
                <saml2:AuthnContextClassRef>urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml2:AuthnContextClassRef>\
                </saml2:AuthnContext>\
                </saml2:AuthnStatement>\
                <saml2:AttributeStatement>\
                <saml2:Attribute Name="api_key">\
                <saml2:AttributeValue>%s</saml2:AttributeValue>\
                </saml2:Attribute>\
                </saml2:AttributeStatement>\
                </saml2:Assertion>"""
                .formatted(assertionId, issueInstant, username, notOnOrAfter, audience,
                        notBefore, notOnOrAfter, issueInstant, sessionIndex, apiKey);
    }

    private String signSamlAssertion(String assertionXml, String privateKeyPem, String certificatePem)
            throws Exception {
        // Parse private key - support both PKCS#1 (RSA PRIVATE KEY) and PKCS#8 (PRIVATE KEY)
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);

        // Parse X.509 certificate
        String certContent = certificatePem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");
        byte[] certBytes = Base64.getDecoder().decode(certContent);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));

        // Parse assertion XML into DOM
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(assertionXml)));

        // Register ID attribute so XML Signature can resolve #id references
        doc.getDocumentElement().setIdAttribute("ID", true);

        // Create XML Digital Signature
        XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");

        // Reference: enveloped signature over the assertion
        String assertionId = doc.getDocumentElement().getAttribute("ID");
        Reference ref = sigFactory.newReference(
                "#" + assertionId,
                sigFactory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(
                        sigFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        sigFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null)
                ),
                null, null
        );

        // SignedInfo
        SignedInfo signedInfo = sigFactory.newSignedInfo(
                sigFactory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                sigFactory.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null),
                List.of(ref)
        );

        // KeyInfo with X509 certificate
        KeyInfoFactory kif = sigFactory.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(List.of(cert));
        KeyInfo keyInfo = kif.newKeyInfo(List.of(x509Data));

        // Create and sign
        javax.xml.crypto.dsig.XMLSignature xmlSignature = sigFactory.newXMLSignature(signedInfo, keyInfo);
        DOMSignContext signContext = new DOMSignContext(privateKey, doc.getDocumentElement());

        // Insert signature after Issuer element
        NodeList issuers = doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Issuer");
        if (issuers.getLength() > 0) {
            signContext.setNextSibling(issuers.item(0).getNextSibling());
        }

        xmlSignature.sign(signContext);

        // Serialize signed document back to string
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    // ===== Helpers =====

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS#1 format → convert to PKCS#8 DER via openssl
            Path tempKey = Files.createTempFile("pkcs1-", ".pem");
            try {
                Files.writeString(tempKey, pem);
                Process process = new ProcessBuilder("openssl", "pkcs8", "-topk8",
                        "-inform", "PEM", "-outform", "DER",
                        "-in", tempKey.toString(), "-nocrypt")
                        .redirectErrorStream(true)
                        .start();
                byte[] pkcs8Der = process.getInputStream().readAllBytes();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new BizException("私钥格式转换失败 (openssl exit " + exitCode + ")");
                }
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
            } finally {
                Files.deleteIfExists(tempKey);
            }
        } else {
            // PKCS#8 format
            String keyContent = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        }
    }

    private String normalizeBaseUrl(String url) {
        // Remove trailing /odata/v2 or /odata/v2/ if present
        return url.replaceAll("/odata/v2/?$", "").replaceAll("/$", "");
    }

    private Map<String, String> requireActiveIasConfig() {
        Map<String, String> cfg = getActiveConfig("IAS");
        if (cfg == null || cfg.isEmpty()) {
            throw new BizException("IAS 单点未启用，请先在连接配置中激活 IAS");
        }
        return cfg;
    }

    private IasConnectionConfig resolveIasConnectionConfig(Map<String, String> cfg, boolean requireTokenFlow) {
        String tenantBaseUrl = firstNonBlank(
                cfg.get("tenantBaseUrl"),
                cfg.get("iasBaseUrl"),
                cfg.get("baseUrl"));
        String authorizeUrl = firstNonBlank(
                cfg.get("authorizeUrl"),
                cfg.get("authorize_url"),
                buildIasEndpointUrl(tenantBaseUrl, "/oauth2/authorize"));
        String tokenUrl = firstNonBlank(
                cfg.get("tokenUrl"),
                cfg.get("token_url"),
                buildIasEndpointUrl(tenantBaseUrl, "/oauth2/token"));
        String userInfoUrl = firstNonBlank(
                cfg.get("userInfoUrl"),
                cfg.get("user_info_url"),
                buildIasEndpointUrl(tenantBaseUrl, "/oauth2/userinfo"));
        String clientId = firstNonBlank(cfg.get("clientId"), cfg.get("client_id"));
        String clientSecret = firstNonBlank(cfg.get("clientSecret"), cfg.get("client_secret"));
        String redirectUri = firstNonBlank(
                cfg.get("redirectUri"),
                cfg.get("redirect_uri"),
                defaultIasRedirectUri());

        if (authorizeUrl.isBlank() || clientId.isBlank() || redirectUri.isBlank()) {
            throw new BizException("IAS 单点未配置完整，请填写 tenantBaseUrl/baseUrl 或 authorizeUrl，并填写 clientId；redirectUri 可由 app.base-url 自动补齐");
        }
        if (requireTokenFlow && (tokenUrl.isBlank() || userInfoUrl.isBlank() || clientSecret.isBlank())) {
            throw new BizException("IAS 单点未配置完整，请填写 tokenUrl / userInfoUrl / clientSecret，或填写 tenantBaseUrl/baseUrl 自动推导地址");
        }

        return new IasConnectionConfig(
                authorizeUrl,
                tokenUrl,
                userInfoUrl,
                clientId,
                clientSecret,
                redirectUri);
    }

    private String defaultIasRedirectUri() {
        String base = normalizedAppBaseUrl();
        if (base.isBlank()) {
            return "";
        }
        return base + "/api/v1/auth/ias/oauth";
    }

    private String defaultIasFrontendSuccessUrl() {
        String base = normalizedAppBaseUrl();
        if (base.isBlank()) {
            return DEFAULT_IAS_FRONTEND_SUCCESS_PATH;
        }
        return base + DEFAULT_IAS_FRONTEND_SUCCESS_PATH;
    }

    private String defaultIasFrontendErrorUrl() {
        String base = normalizedAppBaseUrl();
        if (base.isBlank()) {
            return DEFAULT_IAS_FRONTEND_ERROR_PATH;
        }
        return base + DEFAULT_IAS_FRONTEND_ERROR_PATH;
    }

    private String normalizedAppBaseUrl() {
        return safeTrim(appBaseUrl).replaceAll("/+$", "");
    }

    private String sanitizeIasRedirectMessage(String message) {
        String compact = safeTrim(message).replaceAll("[\\r\\n\\t]+", " ");
        if (compact.isBlank()) {
            return "IAS 单点登录失败";
        }
        return abbreviate(compact, 160);
    }

    private String buildIasEndpointUrl(String baseUrl, String path) {
        if (safeTrim(baseUrl).isBlank()) {
            return "";
        }
        return buildExternalUrl(baseUrl, path);
    }

    private String normalizeHrdcAuthType(String authType) {
        String normalized = safeTrim(authType).isBlank()
                ? "none"
                : authType.trim().toLowerCase(Locale.ROOT);
        if (!HRDC_AUTH_TYPES.contains(normalized)) {
            throw new BizException("不支持的 HRDC 鉴权方式: " + authType);
        }
        return normalized;
    }

    private String buildExternalUrl(String baseUrl, String path) {
        String normalizedPath = safeTrim(path);
        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) {
            return normalizedPath;
        }
        String normalizedBase = safeTrim(baseUrl);
        if (normalizedPath.isBlank()) {
            return normalizedBase;
        }
        if (normalizedBase.endsWith("/") && normalizedPath.startsWith("/")) {
            return normalizedBase + normalizedPath.substring(1);
        }
        if (!normalizedBase.endsWith("/") && !normalizedPath.startsWith("/")) {
            return normalizedBase + "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private int parseTimeoutMs(String raw) {
        String normalized = safeTrim(raw);
        if (normalized.isBlank()) {
            return 5000;
        }
        try {
            int timeout = Integer.parseInt(normalized);
            if (timeout < 1000 || timeout > 120000) {
                throw new BizException("超时必须在 1000-120000ms 之间");
            }
            return timeout;
        } catch (NumberFormatException e) {
            throw new BizException("超时必须是数字");
        }
    }

    private boolean parseBoolean(String raw) {
        String normalized = safeTrim(raw).toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "y".equals(normalized);
    }

    private void appendForm(StringBuilder body, String key, String value) {
        if (!body.isEmpty()) {
            body.append('&');
        }
        body.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
    }

    private Map<String, String> parseFormBody(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        String normalized = safeTrim(raw);
        if (normalized.isBlank()) {
            return result;
        }
        for (String pair : normalized.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String normalized = safeTrim(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String singleLineForLog(String value) {
        return safeTrim(value).replace('\r', ' ').replace('\n', ' ');
    }

    private String maskEmailForLog(String value) {
        String normalized = singleLineForLog(value);
        int at = normalized.indexOf('@');
        if (at <= 0) {
            return "";
        }
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = safeTrim(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)) + "...";
    }

    private void deactivateOthers(String type, Long exceptId) {
        List<ExternalConnection> actives = mapper.selectList(
                new LambdaQueryWrapper<ExternalConnection>()
                        .eq(ExternalConnection::getType, type)
                        .eq(ExternalConnection::getIsActive, 1)
                        .ne(ExternalConnection::getId, exceptId));
        for (ExternalConnection c : actives) {
            ExternalConnection u = new ExternalConnection();
            u.setId(c.getId());
            u.setIsActive(0);
            mapper.updateById(u);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> parseConfig(String configJson) {
        try {
            Map<String, Object> cfg = objectMapper.readValue(configJson, Map.class);
            String authType = normalizeAuthTypeForConfig(cfg.get("authType"));
            Map<String, String> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : cfg.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    parsed.put(entry.getKey(), null);
                    continue;
                }
                String text = String.valueOf(value);
                if (!isSensitiveKey(entry.getKey())) {
                    parsed.put(entry.getKey(), text);
                    continue;
                }
                if (!isSensitiveFieldUsedByAuthType(authType, entry.getKey())) {
                    continue;
                }
                try {
                    parsed.put(entry.getKey(), cryptoService.decryptIfNeeded(text));
                } catch (Exception e) {
                    throw new BizException("连接配置字段 " + entry.getKey() + " 解密失败，请重新保存该连接配置");
                }
            }
            return parsed;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("连接配置解析失败: " + e.getMessage());
        }
    }

    private String maskConfig(String configJson) {
        try {
            Map<String, Object> cfg = objectMapper.readValue(configJson, new TypeReference<>() {});
            for (Map.Entry<String, Object> entry : cfg.entrySet()) {
                String key = entry.getKey();
                Object raw = entry.getValue();
                if (raw == null || !isSensitiveKey(key)) {
                    continue;
                }
                String val = String.valueOf(raw);
                String displayValue = val;
                try {
                    displayValue = cryptoService.decryptIfNeeded(val);
                } catch (Exception e) {
                    cfg.put(key, "****");
                    continue;
                }
                if (displayValue.length() > 100) {
                    // Long values like PEM keys: just show type indicator.
                    cfg.put(key, displayValue.substring(0, 20) + "...(已配置)");
                } else {
                    cfg.put(key, displayValue.length() > 4 ? displayValue.substring(0, 4) + "****" : "****");
                }
            }
            return objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ExternalConnectionResponse toResponse(ExternalConnection connection) {
        return ExternalConnectionResponse.from(connection, maskConfig(connection.getConfig()));
    }

    private ConnectionConfig toConnectionConfig(ExternalConnection connection) {
        return new ConnectionConfig(
                connection.getId(),
                connection.getType(),
                connection.getName(),
                connection.getStatus(),
                connection.getIsActive(),
                connection.getLastTestedAt(),
                connection.getLastTestResult(),
                parseConfig(connection.getConfig()));
    }

    private ConnectionConfig toConnectionOptionConfig(ExternalConnection connection) {
        return new ConnectionConfig(
                connection.getId(),
                connection.getType(),
                connection.getName(),
                connection.getStatus(),
                connection.getIsActive(),
                connection.getLastTestedAt(),
                connection.getLastTestResult(),
                parseConfigForOption(connection.getConfig()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseConfigForOption(String configJson) {
        try {
            Map<String, Object> cfg = objectMapper.readValue(configJson, Map.class);
            Map<String, String> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : cfg.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    parsed.put(entry.getKey(), null);
                    continue;
                }
                if (isSensitiveKey(entry.getKey())) {
                    continue;
                }
                parsed.put(entry.getKey(), String.valueOf(value));
            }
            return parsed;
        } catch (Exception e) {
            throw new BizException("连接配置解析失败: " + e.getMessage());
        }
    }

    private String mergeConfig(String oldConfigJson, String newConfigJson) {
        try {
            Map<String, Object> oldCfg = oldConfigJson == null || oldConfigJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(oldConfigJson, new TypeReference<>() {});
            Map<String, Object> newCfg = objectMapper.readValue(newConfigJson, new TypeReference<>() {});
            for (String key : new ArrayList<>(newCfg.keySet())) {
                if (isSensitiveKey(key)) {
                    String newVal = String.valueOf(newCfg.get(key));
                    if ((newVal.contains("****") || newVal.contains("...(已配置)")) && oldCfg.containsKey(key)) {
                        newCfg.put(key, oldCfg.get(key));
                    }
                }
            }
            removeUnusedAuthFields(newCfg);
            return objectMapper.writeValueAsString(newCfg);
        } catch (Exception e) {
            return newConfigJson;
        }
    }

    private String encryptConfig(String configJson) {
        try {
            Map<String, Object> cfg = objectMapper.readValue(configJson, new TypeReference<>() {});
            removeUnusedAuthFields(cfg);
            for (Map.Entry<String, Object> entry : cfg.entrySet()) {
                if (isSensitiveKey(entry.getKey()) && entry.getValue() != null) {
                    String value = String.valueOf(entry.getValue()).trim();
                    entry.setValue(cryptoService.encryptIfNeeded(value));
                }
            }
            return objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new BizException("连接配置加密失败: " + e.getMessage());
        }
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return NORMALIZED_SENSITIVE_KEYS.contains(normalized);
    }

    private void removeUnusedAuthFields(Map<String, Object> cfg) {
        if (cfg == null || cfg.isEmpty()) return;
        String authType = normalizeAuthTypeForConfig(cfg.get("authType"));
        if (authType.isBlank()) return;
        cfg.keySet().removeIf(key -> isSensitiveKey(key) && !isSensitiveFieldUsedByAuthType(authType, key));
    }

    private boolean isSensitiveFieldUsedByAuthType(String authType, String key) {
        if (authType == null || authType.isBlank() || key == null) return true;
        String normalizedKey = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return switch (authType) {
            case "saml_bearer" -> !Set.of("password", "clientsecret", "secret").contains(normalizedKey);
            case "basic" -> !Set.of(
                    "privatekeypem", "privatekey", "apikey", "apikeyvalue",
                    "clientsecret", "secret", "testauthcode").contains(normalizedKey);
            case "none" -> false;
            default -> true;
        };
    }

    private String normalizeAuthTypeForConfig(Object authType) {
        return authType == null ? "" : String.valueOf(authType).trim().toLowerCase(Locale.ROOT);
    }
}
