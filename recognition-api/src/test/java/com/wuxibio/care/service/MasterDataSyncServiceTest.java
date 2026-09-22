package com.wuxibio.care.service;

import com.sun.net.httpserver.HttpServer;
import com.wuxibio.care.entity.ExternalConnection;
import com.wuxibio.care.entity.EmployeeAssignment;
import com.wuxibio.care.entity.QueryConfig;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.mapper.ExternalConnectionMapper;
import com.wuxibio.care.mapper.EmployeeAssignmentMapper;
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
    @Mock private EmployeeAssignmentMapper employeeAssignmentMapper;
    @Mock private EmployeeAssignmentService employeeAssignmentService;
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
                employeeAssignmentMapper,
                employeeAssignmentService,
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
        user.setEmployeeType("REG");
        user.setAssignmentClass("ST");
        user.setManagementJobLevel("21009");
        user.setProfessionalJobLevel("13009");
        user.setJobGrade("M2-1");
        user.setDateOfBirth(LocalDate.of(1990, 2, 3));
        user.setBenefitsEligibilityStartDate(LocalDate.of(2020, 7, 8));
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
        assertEquals("REG", tokens.get("EmployeeType"));
        assertEquals("REG", tokens.get("employeeType"));
        assertEquals("ST", tokens.get("AssignmentClass"));
        assertEquals("ST", tokens.get("assignmentClass"));
        assertEquals("21009", tokens.get("ManagementJobLevel"));
        assertEquals("21009", tokens.get("managementJobLevel"));
        assertEquals("13009", tokens.get("ProfessionalJobLevel"));
        assertEquals("13009", tokens.get("professionalJobLevel"));
        assertEquals("M2-1", tokens.get("JobGrade"));
        assertEquals("M2-1", tokens.get("jobGrade"));
        assertEquals("1990-02-03", tokens.get("DateOfBirth"));
        assertEquals("1990-02-03", tokens.get("dateOfBirth"));
        assertEquals("2020-07-08", tokens.get("BenefitsEligibilityStartDate"));
        assertEquals("2020-07-08", tokens.get("benefitsEligibilityStartDate"));
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
                      {"userId":"E1001","displayName":"Alice","email":"must-not-sync-without-mapping@example.org","dateOfBirth":"1990-02-03","empInfo":{"benefitsEligibilityStartDate":"2020-07-08","assignmentClass":"ST","jobInfoNav":{"results":[{"customString2":"ORG-03A","customString12":"ORG-04A","customString13":"ORG-05A","customString10":"21009","customString15":"13009","customString47":"M2-1","employeeTypeNav":{"externalCode":"REG"}}]}}},
                      {"userId":"E1002","displayName":"Bob","email":"must-not-sync-without-mapping@example.org","dateOfBirth":"1991-04-05","empInfo":{"benefitsEligibilityStartDate":"2021-09-10","assignmentClass":"GA","jobInfoNav":{"results":[{"customString2":"ORG-03B","customString12":"ORG-04B","customString13":"ORG-05B","customString10":"22001","customString15":"14001","customString47":"P3-1","employeeTypeNav":{"externalCode":"CONT"}}]}}}
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
            when(fieldMappingService.getSourceFieldToTargetFieldMapByConfig(10L))
                    .thenReturn(Map.ofEntries(
                            Map.entry("displayName", "name"),
                            Map.entry("empInfo/jobInfoNav/results/customString2", "thirdDepartment"),
                            Map.entry("empInfo/jobInfoNav/results/customString12", "fourthDepartment"),
                            Map.entry("empInfo/jobInfoNav/results/customString13", "fifthDepartment"),
                            Map.entry("dateOfBirth", "dateOfBirth"),
                            Map.entry("empInfo/benefitsEligibilityStartDate", "benefitsEligibilityStartDate"),
                            Map.entry("empInfo/assignmentClass", "assignmentClass"),
                            Map.entry("empInfo/jobInfoNav/results/customString10", "managementJobLevel"),
                            Map.entry("empInfo/jobInfoNav/results/customString15", "professionalJobLevel"),
                            Map.entry("empInfo/jobInfoNav/results/customString47", "jobGrade"),
                            Map.entry("empInfo/jobInfoNav/results/employeeTypeNav/externalCode", "employeeType")));
            when(sysRoleMapper.selectOne(any())).thenReturn(employeeRole);
            when(sysUserMapper.selectList(any())).thenReturn(List.of());
            when(passwordEncoder.encode(anyString())).thenReturn("shared-unusable-hash");

            AtomicLong userId = new AtomicLong(100L);
            List<SysUser> insertedUsers = new ArrayList<>();
            List<EmployeeAssignment> insertedAssignments = new ArrayList<>();
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser user = invocation.getArgument(0);
                user.setId(userId.incrementAndGet());
                insertedUsers.add(user);
                return 1;
            });
            when(employeeAssignmentMapper.insert(any(EmployeeAssignment.class))).thenAnswer(invocation -> {
                EmployeeAssignment assignment = invocation.getArgument(0);
                insertedAssignments.add(assignment);
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
            assertEquals(List.of("REG", "CONT"),
                    insertedUsers.stream().map(SysUser::getEmployeeType).toList());
            assertEquals(List.of("ST", "GA"),
                    insertedUsers.stream().map(SysUser::getAssignmentClass).toList());
            assertEquals(List.of("21009", "22001"),
                    insertedUsers.stream().map(SysUser::getManagementJobLevel).toList());
            assertEquals(List.of("13009", "14001"),
                    insertedUsers.stream().map(SysUser::getProfessionalJobLevel).toList());
            assertEquals(List.of("M2-1", "P3-1"),
                    insertedUsers.stream().map(SysUser::getJobGrade).toList());
            assertEquals(List.of(LocalDate.of(1990, 2, 3), LocalDate.of(1991, 4, 5)),
                    insertedUsers.stream().map(SysUser::getDateOfBirth).toList());
            assertEquals(List.of(LocalDate.of(2020, 7, 8), LocalDate.of(2021, 9, 10)),
                    insertedUsers.stream().map(SysUser::getBenefitsEligibilityStartDate).toList());
            assertEquals(java.util.Arrays.asList(null, null),
                    insertedUsers.stream().map(SysUser::getEmail).toList());
            assertEquals(2, insertedAssignments.size());
            verify(passwordEncoder, times(1)).encode(anyString());
            verify(sysUserRoleMapper, times(2)).insert(any(SysUserRole.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syncGroupsMultipleSfAssignmentsIntoOnePersonAndProjectsThePrimaryAssignment() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/odata/v2/User", exchange -> {
            byte[] body = """
                    {"d":{"results":[
                      {"userId":"HOME-1001","isPrimaryAssignment":false,"hireDate":"2018-06-01","personKeyNav":{"personIdExternal":"1001"},"empInfo":{"benefitsEligibilityStartDate":"2018-07-01","assignmentClass":"ST","jobInfoNav":{"results":[{"countryOfCompany":"CN"}]}}},
                      {"userId":"GA-1001-A","isPrimaryAssignment":false,"personKeyNav":{"personIdExternal":"1001"},"empInfo":{"assignmentClass":"GA","jobInfoNav":{"results":[{"countryOfCompany":"US"}]}}},
                      {"userId":"GA-1001-B","isPrimaryAssignment":true,"personKeyNav":{"personIdExternal":"1001"},"empInfo":{"assignmentClass":"GA","jobInfoNav":{"results":[{"countryOfCompany":"SG"}]}}}
                    ]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            QueryConfig config = new QueryConfig();
            config.setId(11L);
            config.setConnectionId(21L);
            config.setEmployeeIdField("personKeyNav/personIdExternal");
            config.setQueryPath("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/odata/v2/User?$format=json");

            ExternalConnection connection = new ExternalConnection();
            connection.setId(21L);
            connection.setType("SuccessFactors");
            connection.setConfig("{}");

            SysRole employeeRole = new SysRole();
            employeeRole.setId(31L);
            employeeRole.setName(MasterDataSyncService.EMPLOYEE_ROLE_NAME);

            when(connectionMapper.selectById(21L)).thenReturn(connection);
            when(connectionService.parseConfig("{}"))
                    .thenReturn(Map.of("apiBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort()));
            when(connectionService.buildSFAuthHeader(any())).thenReturn("Bearer test");
            when(fieldMappingService.getSourceFieldToTargetFieldMapByConfig(11L)).thenReturn(Map.of(
                    "empInfo/assignmentClass", "assignmentClass",
                    "hireDate", "hireDate",
                    "empInfo/benefitsEligibilityStartDate", "benefitsEligibilityStartDate",
                    "empInfo/jobInfoNav/results/countryOfCompany", "country"));
            when(sysRoleMapper.selectOne(any())).thenReturn(employeeRole);
            when(sysUserMapper.selectList(any())).thenReturn(List.of());
            when(employeeAssignmentMapper.selectList(any())).thenReturn(List.of());
            when(passwordEncoder.encode(anyString())).thenReturn("shared-unusable-hash");

            List<SysUser> people = new ArrayList<>();
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser person = invocation.getArgument(0);
                person.setId(501L);
                people.add(person);
                return 1;
            });
            List<EmployeeAssignment> assignments = new ArrayList<>();
            when(employeeAssignmentMapper.insert(any(EmployeeAssignment.class))).thenAnswer(invocation -> {
                assignments.add(invocation.getArgument(0));
                return 1;
            });

            MasterDataSyncService.SyncResult result = service.syncFromPersonConfig(config);

            assertEquals(3, result.total());
            assertEquals(1, result.inserted());
            assertEquals(1, people.size());
            assertEquals("1001", people.get(0).getEmployeeId());
            assertEquals("GA", people.get(0).getAssignmentClass());
            assertEquals("SG", people.get(0).getCountry());
            assertEquals(3, assignments.size());
            assertEquals(List.of("HOME-1001", "GA-1001-A", "GA-1001-B"),
                    assignments.stream().map(EmployeeAssignment::getSfUserId).toList());
            assertEquals(List.of(0, 0, 1),
                    assignments.stream().map(EmployeeAssignment::getIsPrimaryAssignment).toList());
            assertEquals(List.of(
                            LocalDate.of(2018, 6, 1),
                            LocalDate.of(2018, 6, 1),
                            LocalDate.of(2018, 6, 1)),
                    assignments.stream().map(EmployeeAssignment::getHireDate).toList());
            assertEquals(List.of(
                            LocalDate.of(2018, 7, 1),
                            LocalDate.of(2018, 7, 1),
                            LocalDate.of(2018, 7, 1)),
                    assignments.stream().map(EmployeeAssignment::getBenefitsEligibilityStartDate).toList());
            assertEquals(LocalDate.of(2018, 6, 1), people.get(0).getHireDate());
            assertEquals(LocalDate.of(2018, 7, 1), people.get(0).getBenefitsEligibilityStartDate());
        } finally {
            server.stop(0);
        }
    }
}
