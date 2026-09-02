package com.wuxibio.care.service;

import com.sun.net.httpserver.HttpServer;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.QueryConfigMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataSyncServiceTest {

    @Mock private QueryConfigMapper queryConfigMapper;
    @Mock private ExternalConnectionMapper connectionMapper;
    @Mock private ExternalConnectionService connectionService;
    @Mock private FieldMappingService fieldMappingService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysRoleMapper sysRoleMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private MasterDataSyncService service;

    @BeforeEach
    void setUp() {
        service = new MasterDataSyncService(
                queryConfigMapper,
                connectionMapper,
                connectionService,
                fieldMappingService,
                sysUserMapper,
                sysRoleMapper,
                sysUserRoleMapper,
                passwordEncoder);
    }

    @Test
    void getTokenValuesByEmployeeIds_exposesCanonicalAndLegacyAliasesIncludingEmail() {
        SysUser user = new SysUser();
        user.setEmployeeId("E1001");
        user.setName("Alice");
        user.setEmail("alice.master@example.org");
        user.setDepartment("HR");
        user.setCompanyName("WuXi");
        user.setPositionCode("POS-1001");
        user.setDivision("DIV-01");
        user.setThirdDepartment("ORG-03");
        user.setFourthDepartment("ORG-04");
        user.setFifthDepartment("ORG-05");
        user.setDingtalkUserId("dt_alice");

        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));

        Map<String, Map<String, String>> result = service.getTokenValuesByEmployeeIds(List.of("E1001"));
        Map<String, String> tokens = result.get("E1001");

        assertEquals("E1001", tokens.get("EmployeeId"));
        assertEquals("E1001", tokens.get("employeeId"));
        assertEquals("Alice", tokens.get("Name"));
        assertEquals("Alice", tokens.get("name"));
        assertEquals("alice.master@example.org", tokens.get("Email"));
        assertEquals("alice.master@example.org", tokens.get("email"));
        assertEquals("HR", tokens.get("Department"));
        assertEquals("HR", tokens.get("department"));
        assertEquals("WuXi", tokens.get("CompanyName"));
        assertEquals("WuXi", tokens.get("companyName"));
        assertEquals("POS-1001", tokens.get("PositionCode"));
        assertEquals("POS-1001", tokens.get("positionCode"));
        assertEquals("DIV-01", tokens.get("Division"));
        assertEquals("DIV-01", tokens.get("division"));
        assertEquals("ORG-03", tokens.get("ThirdDepartment"));
        assertEquals("ORG-03", tokens.get("thirdDepartment"));
        assertEquals("ORG-04", tokens.get("FourthDepartment"));
        assertEquals("ORG-04", tokens.get("fourthDepartment"));
        assertEquals("ORG-05", tokens.get("FifthDepartment"));
        assertEquals("ORG-05", tokens.get("fifthDepartment"));
        assertEquals("dt_alice", tokens.get("DingTalkUserId"));
        assertEquals("dt_alice", tokens.get("dingtalkUserId"));
    }

    @Test
    void parseSourceDate_supportsSuccessFactorsAndIsoDateFormats() {
        long timestamp = Instant.parse("2026-07-20T16:00:00Z").toEpochMilli();

        assertEquals(LocalDate.of(2026, 7, 21),
                MasterDataSyncService.parseSourceDate("/Date(" + timestamp + ")/"));
        assertEquals(LocalDate.of(2027, 1, 31),
                MasterDataSyncService.parseSourceDate("2027-01-31T00:00:00Z"));
        assertEquals(LocalDate.of(2028, 5, 1),
                MasterDataSyncService.parseSourceDate("2028-05-01"));
    }

    @Test
    void syncReusesOneUnusablePasswordHashWithinTheImportBatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/odata/v2/User", exchange -> {
            byte[] body = """
                    {"d":{"results":[
                      {"userId":"E1001","displayName":"Alice","empInfo":{"jobInfoNav":{"results":[{"customString2":"ORG-03A","customString12":"ORG-04A","customString13":"ORG-05A"}]}}},
                      {"userId":"E1002","displayName":"Bob","empInfo":{"jobInfoNav":{"results":[{"customString2":"ORG-03B","customString12":"ORG-04B","customString13":"ORG-05B"}]}}}
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            QueryConfig config = new QueryConfig();
            config.setId(10L);
            config.setConnectionId(20L);
            config.setEmployeeIdField("userId");
            config.setQueryPath("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/odata/v2/User?$format=json");

            ExternalConnection connection = new ExternalConnection();
            connection.setId(20L);
            connection.setType("SuccessFactors");
            connection.setConfig("{}");

            SysRole employeeRole = new SysRole();
            employeeRole.setId(30L);
            employeeRole.setName(MasterDataSyncService.EMPLOYEE_ROLE_NAME);

            when(connectionMapper.selectById(20L)).thenReturn(connection);
            when(connectionService.parseConfig("{}"))
                    .thenReturn(Map.of("apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort()));
            when(connectionService.buildSFAuthHeader(any())).thenReturn("Bearer test");
            when(fieldMappingService.getSourceFieldToTokenMapByConfig(10L))
                    .thenReturn(Map.of(
                            "displayName", "name",
                            "empInfo/jobInfoNav/results/customString2", "thirdDepartment",
                            "empInfo/jobInfoNav/results/customString12", "fourthDepartment",
                            "empInfo/jobInfoNav/results/customString13", "fifthDepartment"));
            when(sysRoleMapper.selectOne(any())).thenReturn(employeeRole);
            when(sysUserMapper.selectList(any())).thenReturn(List.of());
            when(passwordEncoder.encode(anyString())).thenReturn("shared-unusable-hash");

            AtomicLong userId = new AtomicLong(100L);
            List<SysUser> insertedUsers = new ArrayList<>();
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser user = invocation.getArgument(0);
                user.setId(userId.incrementAndGet());
                insertedUsers.add(user);
                return 1;
            });

            MasterDataSyncService.SyncResult result = service.syncFromPersonConfig(config);

            assertEquals(2, result.total());
            assertEquals(2, result.inserted());
            assertEquals(List.of("shared-unusable-hash", "shared-unusable-hash"),
                    insertedUsers.stream().map(SysUser::getPassword).toList());
            assertEquals(List.of("ORG-03A", "ORG-03B"),
                    insertedUsers.stream().map(SysUser::getThirdDepartment).toList());
            assertEquals(List.of("ORG-04A", "ORG-04B"),
                    insertedUsers.stream().map(SysUser::getFourthDepartment).toList());
            assertEquals(List.of("ORG-05A", "ORG-05B"),
                    insertedUsers.stream().map(SysUser::getFifthDepartment).toList());
            verify(passwordEncoder, times(1)).encode(anyString());
            verify(sysUserRoleMapper, times(2)).insert(any(SysUserRole.class));
        } finally {
            server.stop(0);
        }
    }
}
