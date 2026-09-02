package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskContextChangeLog;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskContextChangeLogMapper;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskStatusHistoryMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunCenterServiceTest {

    @Mock private TaskRunMapper taskRunMapper;
    @Mock private TaskRecipientItemMapper taskRecipientItemMapper;
    @Mock private TaskStatusHistoryMapper taskStatusHistoryMapper;
    @Mock private TaskContextChangeLogMapper taskContextChangeLogMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private TemplateChannelVariantMapper templateChannelVariantMapper;
    @Mock private TemplateCenterService templateCenterService;
    @Mock private IntegrationLogService integrationLogService;
    @Mock private AuditLogService auditLogService;
    @Mock private MessageChannel messageChannel;

    private RunCenterService service;

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, TaskRun.class);
        TableInfoHelper.initTableInfo(assistant, TaskRecipientItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new RunCenterService(
                taskRunMapper,
                taskRecipientItemMapper,
                taskStatusHistoryMapper,
                taskContextChangeLogMapper,
                taskTemplateMapper,
                sysUserMapper,
                templateChannelVariantMapper,
                templateCenterService,
                integrationLogService,
                auditLogService,
                java.util.List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageRuns_globalAdminCanSeeAllRuns() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(run(11L, "operator2"), run(12L, "operator3")));

        Map<String, Object> result = service.pageRuns(1, 20, null);

        assertEquals(2, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(List.of(11L, 12L), records.stream().map(row -> row.get("id")).toList());
    }

    @Test
    void pageRuns_nonGlobalAdminOnlySeesOwnStartedRuns() {
        authenticate(7L, "operator7", "ROLE_2");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(run(11L, "operator7"), run(12L, "operator8")));

        Map<String, Object> result = service.pageRuns(1, 20, "Completed");

        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(List.of(11L), records.stream().map(row -> row.get("id")).toList());
    }

    @Test
    void pageRuns_nonGlobalAdminOnlySeesOwnAutoRuns() {
        authenticate(7L, "operator7", "ROLE_2");
        TaskRun ownAutoRun = run(11L, "operator7");
        ownAutoRun.setTriggerMode("Auto");
        TaskRun otherAutoRun = run(12L, "operator8");
        otherAutoRun.setTriggerMode("Auto");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(ownAutoRun, otherAutoRun));

        Map<String, Object> result = service.pageRuns(1, 20, null, null, "Auto");

        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(List.of(11L), records.stream().map(row -> row.get("id")).toList());
    }

    @Test
    void pageRuns_enrichesTemplateFieldsAndNormalizesPendingApprovalViewStatus() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        TaskRun pendingRun = run(11L, "operator2");
        pendingRun.setRunNo("RUN-CARE-001");
        pendingRun.setTaskTemplateId(7L);
        pendingRun.setStatus("PendingApproval");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(pendingRun));
        when(taskTemplateMapper.selectBatchIds(any())).thenReturn(List.of(taskTemplate(7L, "CARE-001", "Care Recognition")));

        Map<String, Object> result = service.pageRuns(1, 20, "Pending_Approval", "recognition");

        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        Map<String, Object> row = records.get(0);
        assertEquals(7L, row.get("taskTemplateId"));
        assertEquals("CARE-001", row.get("taskTemplateCode"));
        assertEquals("Care Recognition", row.get("taskTemplateName"));
        assertEquals("PendingApproval", row.get("status"));
        assertEquals("Pending_Approval", row.get("statusNormalized"));
        assertEquals("Pending approval", row.get("statusDisplay"));
    }

    @Test
    void pageRuns_displaysCancelledRunAsWithdrawn() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        TaskRun withdrawnRun = run(13L, "operator2");
        withdrawnRun.setStatus("Cancelled");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(withdrawnRun));

        Map<String, Object> result = service.pageRuns(1, 20, "Cancelled");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(1, records.size());
        assertEquals("Cancelled", records.get(0).get("status"));
        assertEquals("Withdrawn", records.get(0).get("statusDisplay"));
    }

    @Test
    void pageRuns_enrichesOperatorDisplayFromOperatorUserid() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(run(11L, "qa_operator")));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(sysUser("qa_operator", "QA 操作员", "QA0002")));

        Map<String, Object> result = service.pageRuns(1, 20, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        Map<String, Object> row = records.get(0);
        assertEquals("qa_operator", row.get("operatorUserId"));
        assertEquals("QA 操作员", row.get("operatorName"));
        assertEquals("QA0002", row.get("operatorEmployeeId"));
        assertEquals("QA 操作员 / QA0002", row.get("operatorDisplay"));
    }

    @Test
    void pageRuns_keywordDoesNotLeakOtherUsersRuns() {
        authenticate(7L, "operator7", "ROLE_2");
        TaskRun ownRun = run(11L, "operator7");
        ownRun.setRunNo("RUN-OWN-001");
        ownRun.setTaskTemplateId(7L);
        TaskRun otherRun = run(12L, "operator8");
        otherRun.setRunNo("RUN-SECRET-001");
        otherRun.setTaskTemplateId(8L);
        when(taskRunMapper.selectList(any())).thenReturn(List.of(ownRun, otherRun));
        when(taskTemplateMapper.selectBatchIds(any())).thenReturn(List.of(taskTemplate(7L, "CARE-001", "Care Recognition")));

        Map<String, Object> result = service.pageRuns(1, 20, null, "secret");

        assertEquals(0, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertEquals(List.of(), records);
    }

    @Test
    void getRunDetail_enrichesRunTemplateFields() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        TaskRun run = run(11L, "operator2");
        run.setRunNo("RUN-CARE-001");
        run.setTaskTemplateId(7L);
        run.setStatus("Completed_With_Issue");
        when(taskRunMapper.selectById(11L)).thenReturn(run);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of());
        when(taskTemplateMapper.selectById(7L)).thenReturn(taskTemplate(7L, "CARE-001", "Care Recognition"));

        Map<String, Object> result = service.getRunDetail(11L);

        @SuppressWarnings("unchecked")
        Map<String, Object> runView = (Map<String, Object>) result.get("run");
        assertEquals(11L, runView.get("id"));
        assertEquals(7L, runView.get("taskTemplateId"));
        assertEquals("CARE-001", runView.get("taskTemplateCode"));
        assertEquals("Care Recognition", runView.get("taskTemplateName"));
        assertEquals("Completed_With_Issue", runView.get("status"));
        assertEquals("Completed with issue", runView.get("statusDisplay"));
    }

    @Test
    void getRunDetail_nonGlobalAdminCannotReadOtherUsersRun() {
        authenticate(7L, "operator7", "ROLE_2");
        when(taskRunMapper.selectById(11L)).thenReturn(run(11L, "operator8"));

        BizException ex = assertThrows(BizException.class, () -> service.getRunDetail(11L));

        assertEquals(403, ex.getCode());
        assertEquals("无权访问该 Task Run", ex.getMessage());
    }

    @Test
    void updateRecipientContext_rejectSentSuccess() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectById(11L)).thenReturn(run(11L, "operator2"));
        when(taskRecipientItemMapper.selectOne(any())).thenReturn(recipient(21L, 11L, "Sent_Success", "old@example.com"));

        assertThrows(BizException.class, () -> service.updateRecipientContext(
                11L,
                "E1001",
                Map.of("recipient", "new@example.com"),
                "fix recipient"));
    }

    @Test
    void updateRecipientContext_updateRecipientAndWriteAuditLog() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectById(11L)).thenReturn(run(11L, "operator2"));
        TaskRecipientItem recipient = recipient(21L, 11L, "Suspended_Data_Issue", "old@example.com");
        recipient.setRenderSnapshotJson("{\"EmployeeId\":\"E1001\",\"Email\":\"old@example.com\",\"Name\":\"Old Name\"}");
        when(taskRecipientItemMapper.selectOne(any())).thenReturn(recipient);

        service.updateRecipientContext(
                11L,
                "E1001",
                Map.of(
                        "recipient", "new@example.com",
                        "employeeId", "E2002",
                        "Email", "new@example.com",
                        "Name", "New Name"),
                "manual correction");

        ArgumentCaptor<TaskRecipientItem> itemCaptor = ArgumentCaptor.forClass(TaskRecipientItem.class);
        verify(taskRecipientItemMapper).update(itemCaptor.capture(), any());
        assertEquals("new@example.com", itemCaptor.getValue().getRecipient());
        assertEquals("E2002", itemCaptor.getValue().getEmployeeId());
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getValue().getRenderSnapshotJson().contains("\"EmployeeId\":\"E2002\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getValue().getRenderSnapshotJson().contains("\"Email\":\"new@example.com\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getValue().getRenderSnapshotJson().contains("\"Name\":\"New Name\""));

        verify(taskContextChangeLogMapper, times(4)).insert(any(TaskContextChangeLog.class));
        verify(auditLogService).log(any(), any(), any(), any());
    }

    @Test
    void retryRecipient_acceptsSentFailedCanonicalStatus() {
        RunCenterService serviceWithChannel = serviceWithChannel();
        TaskRun run = run(11L, "operator2");
        run.setChannelVariantId(99L);
        TaskRecipientItem failed = recipient(22L, 11L, "Sent_Failed", "failed@example.com");
        failed.setRenderSnapshotJson("{\"Name\":\"Alice\"}");
        TaskRecipientItem sending = recipient(22L, 11L, "Sending", "failed@example.com");
        sending.setRenderSnapshotJson("{\"Name\":\"Alice\"}");
        TaskRecipientItem sent = recipient(22L, 11L, "Sent_Success", "failed@example.com");
        TemplateChannelVariant template = template("Email");

        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectById(11L)).thenReturn(run);
        when(taskRecipientItemMapper.selectOne(any())).thenReturn(failed, failed);
        when(templateChannelVariantMapper.selectById(99L)).thenReturn(template);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of(sent));
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Hello Alice");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        serviceWithChannel.retryRecipient(11L, "E1001", "retry after normalization");

        ArgumentCaptor<TaskRecipientItem> itemCaptor = ArgumentCaptor.forClass(TaskRecipientItem.class);
        verify(taskRecipientItemMapper, times(3)).update(itemCaptor.capture(), any());
        assertEquals("Sending", itemCaptor.getAllValues().get(0).getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__renderedSubject\":\"Hi Alice\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__renderedContent\":\"Hello Alice\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__messageType\":\"email_html\""));
        assertEquals("Sent_Success", itemCaptor.getAllValues().get(2).getStatus());
        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor = ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(messageChannel).send(requestCaptor.capture());
        assertEquals("failed@example.com", requestCaptor.getValue().recipient());
        assertEquals("Hi Alice", requestCaptor.getValue().subject());
        assertEquals("Hello Alice", requestCaptor.getValue().content());
        verify(auditLogService).log(any(), any(), any(), any());
    }

    @Test
    void resumeRecipient_acceptsSuspendedDataIssueCanonicalStatus() {
        RunCenterService serviceWithChannel = serviceWithChannel();
        TaskRun run = run(11L, "operator2");
        run.setChannelVariantId(99L);
        TaskRecipientItem suspended = recipient(23L, 11L, "Suspended_Data_Issue", "suspended@example.com");
        suspended.setRenderSnapshotJson("{\"Name\":\"Bob\"}");
        TaskRecipientItem sending = recipient(23L, 11L, "Sending", "suspended@example.com");
        sending.setRenderSnapshotJson("{\"Name\":\"Bob\"}");
        TaskRecipientItem sent = recipient(23L, 11L, "Sent_Success", "suspended@example.com");
        TemplateChannelVariant template = template("Email");

        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectById(11L)).thenReturn(run);
        when(taskRecipientItemMapper.selectOne(any())).thenReturn(suspended, suspended);
        when(templateChannelVariantMapper.selectById(99L)).thenReturn(template);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(List.of(sent));
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Hello Bob");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        serviceWithChannel.resumeRecipient(11L, "E1001", "resume after normalization");

        ArgumentCaptor<TaskRecipientItem> itemCaptor = ArgumentCaptor.forClass(TaskRecipientItem.class);
        verify(taskRecipientItemMapper, times(3)).update(itemCaptor.capture(), any());
        assertEquals("Sending", itemCaptor.getAllValues().get(0).getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__renderedSubject\":\"Hi Bob\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__renderedContent\":\"Hello Bob\""));
        org.junit.jupiter.api.Assertions.assertTrue(itemCaptor.getAllValues().get(1).getRenderSnapshotJson().contains("\"__messageType\":\"email_html\""));
        assertEquals("Sent_Success", itemCaptor.getAllValues().get(2).getStatus());
        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor = ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(messageChannel).send(requestCaptor.capture());
        assertEquals("suspended@example.com", requestCaptor.getValue().recipient());
        assertEquals("Hi Bob", requestCaptor.getValue().subject());
        assertEquals("Hello Bob", requestCaptor.getValue().content());
        verify(auditLogService).log(any(), any(), any(), any());
    }

    @Test
    void claimPendingApprovalRun_returnsTrueOnlyForAtomicWinner() {
        when(taskRunMapper.update(nullable(TaskRun.class), any())).thenReturn(1, 0);

        assertTrue(service.claimPendingApprovalRun(911008L));
        assertFalse(service.claimPendingApprovalRun(911008L));
    }

    @Test
    void dispatchApprovedRun_usesLockedSnapshotAndCompletesOriginalRun() {
        RunCenterService serviceWithChannel = serviceWithChannel();
        TaskRun sendingRun = run(911008L, "30010499");
        sendingRun.setStatus("Sending");
        sendingRun.setTriggerMode("Manual");
        sendingRun.setChannelVariantId(99L);
        TaskRun completedRun = run(911008L, "30010499");
        completedRun.setStatus("Completed");
        completedRun.setTriggerMode("Manual");
        completedRun.setTotalCount(1);
        completedRun.setSuccessCount(1);
        completedRun.setFailedCount(0);
        completedRun.setSuspendedCount(0);

        TaskRecipientItem pending = recipient(
                921012L, 911008L, "Pending_Approval", "locked@example.com");
        pending.setRenderSnapshotJson("""
                {"Name":"Alice","__renderedSubject":"Locked subject",\
                "__renderedContent":"Locked content",\
                "__renderedChannelPayloadJson":"{\\"locked\\":true}",\
                "__messageType":"email_html"}
                """);
        TaskRecipientItem sent = recipient(
                921012L, 911008L, "Sent_Success", "locked@example.com");

        authenticate(1L, "system-approval-dispatch", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectById(911008L)).thenReturn(sendingRun, completedRun);
        when(taskRecipientItemMapper.selectList(any())).thenReturn(
                List.of(pending), List.of(sent), List.of(sent));
        when(taskRecipientItemMapper.update(nullable(TaskRecipientItem.class), any())).thenReturn(1);
        when(templateChannelVariantMapper.selectById(99L)).thenReturn(template("Email"));

        TaskRun result = serviceWithChannel.dispatchApprovedRun(911008L);

        assertEquals("Completed", result.getStatus());
        ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(messageChannel).send(requestCaptor.capture());
        assertEquals("locked@example.com", requestCaptor.getValue().recipient());
        assertEquals("Locked subject", requestCaptor.getValue().subject());
        assertEquals("Locked content", requestCaptor.getValue().content());
        assertEquals("email_html", requestCaptor.getValue().messageType());
        assertEquals("{\"locked\":true}", requestCaptor.getValue().channelPayloadJson());
        assertEquals("911008", requestCaptor.getValue().metadata().get("taskRunId"));
        verifyNoInteractions(templateCenterService);

        ArgumentCaptor<TaskRun> runCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunMapper).updateById(runCaptor.capture());
        assertEquals("Completed", runCaptor.getValue().getStatus());
        assertEquals(1, runCaptor.getValue().getTotalCount());
        assertEquals(1, runCaptor.getValue().getSuccessCount());
        assertEquals(0, runCaptor.getValue().getFailedCount());
        assertEquals(0, runCaptor.getValue().getSuspendedCount());
    }

    private RunCenterService serviceWithChannel() {
        when(messageChannel.getType()).thenReturn("Email");
        return new RunCenterService(
                taskRunMapper,
                taskRecipientItemMapper,
                taskStatusHistoryMapper,
                taskContextChangeLogMapper,
                taskTemplateMapper,
                sysUserMapper,
                templateChannelVariantMapper,
                templateCenterService,
                integrationLogService,
                auditLogService,
                java.util.List.of(messageChannel));
    }

    private void authenticate(Long userId, String username, String authority) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of(() -> authority));
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private TaskRun run(Long id, String startedBy) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setStartedBy(startedBy);
        run.setStatus("Completed");
        return run;
    }

    private TaskTemplate taskTemplate(Long id, String code, String name) {
        TaskTemplate template = new TaskTemplate();
        template.setId(id);
        template.setCode(code);
        template.setName(name);
        return template;
    }

    private SysUser sysUser(String username, String name, String employeeId) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setName(name);
        user.setEmployeeId(employeeId);
        return user;
    }

    private TaskRecipientItem recipient(Long id, Long runId, String status, String recipient) {
        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(id);
        item.setTaskRunId(runId);
        item.setStatus(status);
        item.setRecipient(recipient);
        item.setEmployeeId("E1001");
        return item;
    }

    private TemplateChannelVariant template(String channel) {
        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(99L);
        template.setChannel(channel);
        template.setSubject("Hi {{Name}}");
        template.setContent("Hello {{Name}}");
        return template;
    }
}
