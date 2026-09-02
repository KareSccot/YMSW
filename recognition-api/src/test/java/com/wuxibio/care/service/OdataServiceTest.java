package com.wuxibio.care.service;

import com.sun.net.httpserver.HttpServer;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.FieldMapping;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.entity.TargetGroupCondition;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.FieldMappingMapper;
import com.wuxibio.care.mapper.QueryConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdataServiceTest {

    @Mock private FieldMappingMapper fieldMappingMapper;
    @Mock private QueryConfigMapper queryConfigMapper;
    @Mock private ExternalConnectionMapper connectionMapper;
    @Mock private ExternalConnectionService connectionService;
    @Mock private MasterDataSyncService masterDataSyncService;

    private OdataService service;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        service = new OdataService(
                new FieldMappingService(fieldMappingMapper),
                queryConfigMapper,
                connectionMapper,
                connectionService,
                masterDataSyncService);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void validateEmployeesInTargetGroup_withoutActiveConfigKeepsCompatibilityAndSkipsValidation() {
        when(queryConfigMapper.selectOne(any())).thenReturn(null);

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of(), denied);
    }

    @Test
    void validateEmployeesInTargetGroup_withMissingConnectionFailsClosed() {
        QueryConfig config = activeConfig();
        when(queryConfigMapper.selectOne(any())).thenReturn(config);
        when(connectionMapper.selectById(config.getConnectionId())).thenReturn(null);

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of("E1001", "E1002"), denied);
    }

    @Test
    void validateEmployeesInTargetGroup_withMissingApiBaseUrlFailsClosed() {
        QueryConfig config = activeConfig();
        ExternalConnection connection = connection();
        when(queryConfigMapper.selectOne(any())).thenReturn(config);
        when(connectionMapper.selectById(config.getConnectionId())).thenReturn(connection);
        when(connectionService.parseConfig(connection.getConfig())).thenReturn(Map.of());

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of("E1001", "E1002"), denied);
    }

    @Test
    void validateEmployeesInTargetGroup_withHttpNon200FailsClosed() throws IOException {
        startServer(500, "{\"error\":\"failed\"}");
        configureActiveConnection("http://127.0.0.1:" + server.getAddress().getPort());

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of("E1001", "E1002"), denied);
    }

    @Test
    void validateEmployeesInTargetGroup_withInvalidJsonFailsClosed() throws IOException {
        startServer(200, "not-json");
        configureActiveConnection("http://127.0.0.1:" + server.getAddress().getPort());

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of("E1001", "E1002"), denied);
    }

    @Test
    void validateEmployeesInTargetGroup_withRequestExceptionFailsClosed() {
        configureActiveConnection("http://[bad");

        Set<String> denied = service.validateEmployeesInTargetGroup(employeeIds(), conditions());

        assertEquals(Set.of("E1001", "E1002"), denied);
    }

    @Test
    void fetchEmployeesByIds_alwaysSelectsConfiguredEmployeeIdField() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        startServer(200, """
                {"d":{"results":[{"userId":"E1001","CompanyName":"WuXi Biologics"}]}}
                """, rawQuery);
        configureActiveConnection("http://127.0.0.1:" + server.getAddress().getPort());
        when(fieldMappingMapper.selectList(any())).thenReturn(List.of(fieldMapping("CompanyName", "CompanyName")));

        Map<String, Map<String, String>> employees = service.fetchEmployeesByIds(List.of("E1001"));

        assertEquals("WuXi Biologics", employees.get("E1001").get("CompanyName"));
        assertEquals("E1001", employees.get("E1001").get("EmployeeId"));
        assertTrue(rawQuery.get().contains("$select=userId,CompanyName"));
    }

    @Test
    void fetchEmployeesByIds_extractsEmployeeIdFromExpandedNavigationField() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        startServer(200, """
                {"d":{"results":[{"personKeyNav":{"personIdExternal":"E1001"},"nickname":"Alice"}]}}
                """, rawQuery);
        configureActiveConnection("http://127.0.0.1:" + server.getAddress().getPort(),
                "personKeyNav/personIdExternal");
        when(fieldMappingMapper.selectList(any())).thenReturn(List.of(fieldMapping("nickname", "name")));

        Map<String, Map<String, String>> employees = service.fetchEmployeesByIds(List.of("E1001"));

        assertEquals("Alice", employees.get("E1001").get("name"));
        assertEquals("E1001", employees.get("E1001").get("employeeId"));
        assertTrue(rawQuery.get().contains("$select=personKeyNav/personIdExternal,nickname"));
    }

    @Test
    void fetchEmployeesByIds_selectsExpandsAndReadsNestedJobInfoFields() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        startServer(200, """
                {"d":{"results":[{
                  "personKeyNav":{"personIdExternal":"E1001"},
                  "empInfo":{"jobInfoNav":{"results":[{
                    "position":"POS-1001",
                    "division":"71000031",
                    "location":"1001",
                    "employeeTypeNav":{"externalCode":"1"},
                    "contractEndDate":"/Date(1790784000000)/"
                  }]}}
                }]}}
                """, rawQuery);
        configureActiveConnection("http://127.0.0.1:" + server.getAddress().getPort(),
                "personKeyNav/personIdExternal");
        when(fieldMappingMapper.selectList(any())).thenReturn(List.of(
                fieldMapping("empInfo/jobInfoNav/results/position", "positionCode"),
                fieldMapping("empInfo/jobInfoNav/results/division", "division"),
                fieldMapping("empInfo/jobInfoNav/results/location", "location"),
                fieldMapping("empInfo/jobInfoNav/results/employeeTypeNav/externalCode", "employeeType"),
                fieldMapping("empInfo/jobInfoNav/results/contractEndDate", "contractEndDate")));

        Map<String, Map<String, String>> employees = service.fetchEmployeesByIds(List.of("E1001"));

        assertEquals("POS-1001", employees.get("E1001").get("positionCode"));
        assertEquals("71000031", employees.get("E1001").get("division"));
        assertEquals("1001", employees.get("E1001").get("location"));
        assertEquals("1", employees.get("E1001").get("employeeType"));
        assertEquals("2026-10-01", employees.get("E1001").get("contractEndDate"));
        assertTrue(rawQuery.get().contains("$select=personKeyNav/personIdExternal,empInfo/jobInfoNav/position,empInfo/jobInfoNav/division,empInfo/jobInfoNav/location,empInfo/jobInfoNav/employeeTypeNav/externalCode,empInfo/jobInfoNav/contractEndDate"));
        assertTrue(rawQuery.get().contains("$expand=personKeyNav,empInfo/jobInfoNav/employeeTypeNav"));
        assertTrue(!rawQuery.get().contains("/results/"));
    }

    private void configureActiveConnection(String apiBaseUrl) {
        configureActiveConnection(apiBaseUrl, "userId");
    }

    private void configureActiveConnection(String apiBaseUrl, String employeeIdField) {
        QueryConfig config = activeConfig();
        config.setEmployeeIdField(employeeIdField);
        ExternalConnection connection = connection();
        when(queryConfigMapper.selectOne(any())).thenReturn(config);
        when(connectionMapper.selectById(config.getConnectionId())).thenReturn(connection);
        when(connectionService.parseConfig(connection.getConfig())).thenReturn(Map.of("apiBaseUrl", apiBaseUrl));
        when(connectionService.buildSFAuthHeader(any())).thenReturn("Bearer token");
    }

    private void startServer(int status, String body) throws IOException {
        startServer(status, body, null);
    }

    private void startServer(int status, String body, AtomicReference<String> rawQuery) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (rawQuery != null) {
                rawQuery.set(exchange.getRequestURI().getRawQuery());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private QueryConfig activeConfig() {
        QueryConfig config = new QueryConfig();
        config.setId(10L);
        config.setConnectionId(20L);
        config.setEmployeeIdField("userId");
        config.setIsActive(1);
        return config;
    }

    private ExternalConnection connection() {
        ExternalConnection connection = new ExternalConnection();
        connection.setId(20L);
        connection.setType("SuccessFactors");
        connection.setConfig("{}");
        return connection;
    }

    private FieldMapping fieldMapping(String odataField, String tokenKey) {
        FieldMapping mapping = new FieldMapping();
        mapping.setSourceField(odataField);
        mapping.setTokenKey(tokenKey);
        return mapping;
    }

    private List<String> employeeIds() {
        return List.of("E1001", "E1002");
    }

    private List<TargetGroupCondition> conditions() {
        TargetGroupCondition condition = new TargetGroupCondition();
        condition.setDimension("department");
        condition.setValue("HR");
        return List.of(condition);
    }
}
