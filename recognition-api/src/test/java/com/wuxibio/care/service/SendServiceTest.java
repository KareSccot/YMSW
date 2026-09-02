package com.wuxibio.care.service;

import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.MdLookupItem;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateFieldBinding;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendServiceTest {

    @Mock private OdataService odataService;
    @Mock private ExternalConnectionService connectionService;
    @Mock private TemplateSenderMailboxService templateSenderMailboxService;
    @Mock private TaskTemplateService taskTemplateService;
    @Mock private TemplateCenterService templateCenterService;
    @Mock private FieldRegistryService fieldRegistryService;
    @Mock private RecipientScopeService recipientScopeService;
    @Mock private ConditionExpressionService conditionExpressionService;
    @Mock private TaskGovernanceService taskGovernanceService;
    @Mock private RunCenterService runCenterService;
    @Mock private IntegrationLogService integrationLogService;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private MasterDataLookupService masterDataLookupService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private MessageChannel emailChannel;
    @Mock private MessageChannel dingTalkChannel;

    private SendService service;

    @BeforeEach
    void setUp() {
        when(emailChannel.getType()).thenReturn("Email");
        lenient().when(connectionService.getActiveConnectionConfig("SMTP"))
                .thenReturn(activeSmtpConnection(Map.of()));
        lenient().when(templateSenderMailboxService.resolveForTemplateHeader(nullable(Long.class)))
                .thenReturn(activeMailboxResolution(Map.of()));
        service = new SendService(
                odataService,
                connectionService,
                templateSenderMailboxService,
                taskTemplateService,
                templateCenterService,
                fieldRegistryService,
                recipientScopeService,
                conditionExpressionService,
                taskGovernanceService,
                runCenterService,
                integrationLogService,
                conditionRuleService,
                masterDataLookupService,
                sysUserMapper,
                List.of(emailChannel));
    }

    @Test
    void resolveMailboxOptionUsesTemplateGroupResolution() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(11L);
        template.setTemplateHeaderId(50L);
        template.setChannel("Email");
        TemplateSenderMailboxService.Resolution resolution = activeMailboxResolution(Map.of());
        SendMailboxOption option = new SendMailboxOption(
                "ACTIVE_SMTP", null, 99L, "Default SMTP", "Default SMTP", "smtp.example.com", "465",
                "sender@example.com", "sender@example.com", "Recognition Platform", null);

        when(taskTemplateService.getExecutableTemplate(7L)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(7L)).thenReturn(List.of(template));
        when(templateSenderMailboxService.resolveForTemplateHeader(50L)).thenReturn(resolution);
        when(templateSenderMailboxService.toOption(resolution)).thenReturn(option);

        SendMailboxOption resolved = service.resolveMailboxOption(7L, 11L);

        assertEquals("ACTIVE_SMTP", resolved.source());
        assertEquals(99L, resolved.externalConnectionId());
    }

    @Test
    void confirmTaskTemplateSend_blocksOutOfScopeRecipientsBeforeStartRun() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001", "E2002")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(
                        Set.of("E2002", "E1001"),
                        "{\"scope\":\"test\"}"));

        List<Map<String, String>> rows = List.of(
                row("E1001", "a@example.com"),
                row("E2002", "b@example.com"));

        BizException ex = assertThrows(
                BizException.class,
                () -> service.confirmTaskTemplateSend(taskTemplateId, templateId, rows));

        assertTrue(ex.getMessage().contains("发送对象超出授权范围"));
        assertTrue(ex.getMessage().contains("E1001"));
        assertTrue(ex.getMessage().contains("E2002"));
        verify(runCenterService, never()).startRun(any(), any(), anyInt(), any(), any(), any(), any());
        verify(taskGovernanceService, never()).checkSendApprovalGate(any());
        verify(taskGovernanceService, never()).consumeApprovalsByTaskRun(any());
    }

    @Test
    void confirmTaskTemplateSend_blocksWhenApprovalGateBlocked_andAutoSubmits() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        List<Map<String, String>> rows = List.of(row("E1001", "user@example.org"));

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(true, List.of(), "HR_SENSITIVE:PENDING"));
        com.wuxibio.care.entity.TaskApprovalInstance approval = new com.wuxibio.care.entity.TaskApprovalInstance();
        approval.setId(777L);
        when(taskGovernanceService.submitApprovals(run.getId())).thenReturn(List.of(approval));

        SendService.SendSummary summary = service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        assertEquals("Pending_Approval", summary.status());
        assertEquals(List.of(777L), summary.approvalIds());
        assertTrue(summary.pendingReason() != null && summary.pendingReason().contains("HR_SENSITIVE"));
        verify(taskGovernanceService).submitApprovals(run.getId());
        verify(runCenterService).markRunPendingApproval(run.getId());
        verify(runCenterService, never()).finishRun(any());
    }

    @Test
    void confirmTaskTemplateSend_consumesApprovalsAfterSuccessfulSend() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        List<Map<String, String>> rows = List.of(row("E1001", "user@example.org"));

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("user@example.org", requestCaptor.getValue().recipient());
        org.junit.jupiter.api.Assertions.assertEquals("Hello E1001", requestCaptor.getValue().subject());
        org.junit.jupiter.api.Assertions.assertEquals("Care message", requestCaptor.getValue().content());
        verify(runCenterService).markSentSuccess(item);
        verify(runCenterService).finishRun(run.getId());
        verify(taskGovernanceService).consumeApprovalsByTaskRun(run.getId());
    }

    @Test
    void confirmTaskTemplateSend_usesTemplateGroupSenderMailboxInMetadataAndRunSnapshot() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setTemplateHeaderId(50L);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        Map<String, String> smtpConfig = new LinkedHashMap<>();
        smtpConfig.put("host", "smtp.special.example.com");
        smtpConfig.put("port", "587");
        smtpConfig.put("username", "special@example.com");
        smtpConfig.put("password", "secret-password");
        smtpConfig.put("useSsl", "false");
        smtpConfig.put("fromAddress", "special@example.com");
        smtpConfig.put("fromName", "Special HR");

        List<Map<String, String>> rows = List.of(row("E1001", "user@example.org"));

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(templateSenderMailboxService.resolveForTemplateHeader(50L)).thenReturn(new TemplateSenderMailboxService.Resolution(
                "SENDER_MAILBOX", 22L, null, "Special HR Mailbox", "smtp.special.example.com", "587",
                "special@example.com", "special@example.com", "Special HR", "Success", smtpConfig,
                Map.of("senderMailboxSource", "SENDER_MAILBOX", "senderMailboxId", "22")));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        assertEquals("SENDER_MAILBOX", requestCaptor.getValue().metadata().get("senderMailboxSource"));
        assertEquals("22", requestCaptor.getValue().metadata().get("senderMailboxId"));

        org.mockito.ArgumentCaptor<String> snapshotCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runCenterService).startRun(eq(taskTemplateId), eq(templateId), eq(1), eq("{\"scope\":\"test\"}"),
                snapshotCaptor.capture(), any(), eq("Manual"));
        assertTrue(snapshotCaptor.getValue().contains("\"source\":\"SENDER_MAILBOX\""));
        assertTrue(snapshotCaptor.getValue().contains("\"senderMailboxId\":22"));
        assertFalse(snapshotCaptor.getValue().contains("secret-password"));
    }

    @Test
    void executeAutoTriggerSend_usesTemplateGroupSenderMailbox() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        taskTemplate.setMode("Auto");
        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(11L);
        template.setTemplateHeaderId(50L);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");
        TaskRun run = new TaskRun();
        run.setId(123L);
        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        Map<String, String> smtpConfig = new LinkedHashMap<>();
        smtpConfig.put("host", "smtp.special.example.com");
        smtpConfig.put("port", "587");
        smtpConfig.put("username", "special@example.com");
        smtpConfig.put("password", "secret-password");
        smtpConfig.put("fromAddress", "special@example.com");
        smtpConfig.put("fromName", "Special HR");
        when(templateSenderMailboxService.resolveForTemplateHeader(50L)).thenReturn(new TemplateSenderMailboxService.Resolution(
                "SENDER_MAILBOX", 22L, null, "Special HR Mailbox", "smtp.special.example.com", "587",
                "special@example.com", "special@example.com", "Special HR", "Success", smtpConfig,
                Map.of("senderMailboxSource", "SENDER_MAILBOX", "senderMailboxId", "22")));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(123L))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(7L, 11L)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.executeAutoTriggerSend(
                taskTemplate,
                template,
                List.of(row("E1001", "user@example.org")),
                "{\"scope\":\"auto\"}",
                "owner");

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        assertEquals("SENDER_MAILBOX", requestCaptor.getValue().metadata().get("senderMailboxSource"));
        assertEquals("22", requestCaptor.getValue().metadata().get("senderMailboxId"));
        org.mockito.ArgumentCaptor<String> snapshotCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runCenterService).startRun(eq(7L), eq(11L), eq(1), eq("{\"scope\":\"auto\"}"),
                snapshotCaptor.capture(), eq("owner"), eq("Auto"));
        assertTrue(snapshotCaptor.getValue().contains("\"senderMailboxId\":22"));
        assertFalse(snapshotCaptor.getValue().contains("secret-password"));
    }

    @Test
    void executeAutoTriggerSend_withPrecreatedRunDoesNotCreateSecondRun() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        taskTemplate.setMode("Auto");
        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(11L);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");
        TaskRun run = new TaskRun();
        run.setId(123L);
        run.setTaskTemplateId(7L);
        run.setChannelVariantId(11L);
        run.setStartedBy("owner");
        run.setTriggerMode("Auto");
        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);

        when(taskGovernanceService.checkSendApprovalGate(123L))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(7L, 11L)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.executeAutoTriggerSend(
                run,
                taskTemplate,
                template,
                List.of(row("E1001", "user@example.org")),
                "{\"scope\":\"auto\"}",
                22L);

        verify(runCenterService, never()).startRun(any(), any(), anyInt(), any(), any(), any(), any());
        verify(runCenterService).updateSystemRunContext(eq(123L), eq(1), eq("{\"scope\":\"auto\"}"), any());
        verify(runCenterService).finishSystemRun(123L);
        verify(runCenterService, never()).finishRun(123L);
        verify(emailChannel).send(any());
    }

    @Test
    void executeAutoTriggerSend_blocksRecipientsOutsideTemplateConditionRule() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(7L);
        taskTemplate.setMode("Auto");
        taskTemplate.setConditionRuleVersionId(20L);
        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(11L);
        template.setStatus("Published");
        template.setChannel("Email");
        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                20L, 10L, "CR_CN", "中国员工", "Active", 3, "Published",
                "{}", "Country 等于 CN", List.of("Country"), null, null, null, null, null);
        when(conditionRuleService.matchEmployeeIds(eq(20L), eq(List.of("E1001", "E1002")), any()))
                .thenReturn(new ConditionRuleService.EmployeeMatchResult(
                        Set.of("E1001"), Set.of("E1002"), Map.of(), rule));

        BizException error = assertThrows(BizException.class, () -> service.executeAutoTriggerSend(
                taskTemplate,
                template,
                List.of(row("E1001", "allowed@example.org"), row("E1002", "denied@example.org")),
                "{\"scope\":\"auto\"}",
                "owner"));

        assertTrue(error.getMessage().contains("E1002"));
        verify(runCenterService, never()).startRun(any(), any(), anyInt(), any(), any(), any(), any());
        verify(emailChannel, never()).send(any());
    }

    @Test
    void confirmTaskTemplateSend_doesNotSuspendCanonicalRowsForSystemAliasBindings() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{email}} {{companyName}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        Map<String, String> row = row("E1001", "user@example.org");
        row.put("CompanyName", "无锡生物");
        List<Map<String, String>> rows = List.of(row);

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of(
                resolvedBinding("email", "Email", "System", "BLOCK"),
                resolvedBinding("companyName", "Company", "System", "BLOCK")));
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        verify(runCenterService, never()).markSuspendedDataIssue(any(), any());
        verify(emailChannel).send(any());
        verify(runCenterService).markSentSuccess(item);
    }

    @Test
    void confirmTaskTemplateSend_doesNotBlockCompanyDomainWhenBlacklistNotConfigured() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        List<Map<String, String>> rows = List.of(row("E1001", "user@wuxibiologics.com"));

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("user@wuxibiologics.com", requestCaptor.getValue().recipient());
        verify(runCenterService).markSentSuccess(item);
    }

    @Test
    void confirmTaskTemplateSend_blocksConfiguredBlacklistDomain() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(templateSenderMailboxService.resolveForTemplateHeader(nullable(Long.class)))
                .thenReturn(activeMailboxResolution(Map.of("emailBlacklist", "@wuxibiologics.com")));

        BizException ex = assertThrows(
                BizException.class,
                () -> service.confirmTaskTemplateSend(taskTemplateId, templateId, List.of(row("E1001", "user@wuxibiologics.com"))));

        assertTrue(ex.getMessage().contains("属于受保护域名"));
        verify(runCenterService, never()).startRun(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void parseExcelByTaskTemplate_usesCanonicalSystemFieldsAndTranslatesCountry() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "EmployeeId", "E1001",
                        "employeeId", "E1001",
                        "Email", "alice.master@example.org",
                        "email", "alice.master@example.org",
                        "Country", "CN",
                        "country", "CN",
                        "CompanyName", "2000",
                        "companyName", "2000")));
        when(masterDataLookupService.batchLookupByCodes(
                eq(MasterDataLookupService.DIMENSION_COUNTRY),
                eq(Set.of("CN")))).thenReturn(Map.of(
                        "CN", new MdLookupItem("CN", "中国", "China", "A")));
        when(masterDataLookupService.batchLookupByCodes(
                eq(MasterDataLookupService.DIMENSION_COMPANY),
                eq(Set.of("2000")))).thenReturn(Map.of(
                        "2000", new MdLookupItem("2000", "无锡生物", "WuXi Biologics", "A")));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));

        Map<String, Object> result = service.parseExcelByTaskTemplate(
                taskTemplateId,
                templateId,
                uploadWorkbook("EmployeeId", "E1001"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows");
        Map<String, String> row = rows.get(0);
        assertEquals("alice.master@example.org", row.get("Email"));
        assertEquals("中国", row.get("Country"));
        assertEquals("无锡生物", row.get("CompanyName"));
        assertFalse(row.containsKey("email"));
        assertFalse(row.containsKey("country"));
        assertFalse(row.containsKey("companyName"));
    }

    @Test
    void parseExcelByTaskTemplate_collapsesUploadedSystemAliasColumns() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1002"))).thenReturn(Map.of(
                "E1002", Map.of("EmployeeId", "E1002")));
        when(masterDataLookupService.batchLookupByCodes(
                eq(MasterDataLookupService.DIMENSION_COUNTRY),
                eq(Set.of("CN")))).thenReturn(Map.of(
                        "CN", new MdLookupItem("CN", "中国", "China", "A")));
        when(masterDataLookupService.batchLookupByCodes(
                eq(MasterDataLookupService.DIMENSION_COMPANY),
                eq(Set.of("2000")))).thenReturn(Map.of(
                        "2000", new MdLookupItem("2000", "无锡生物", "WuXi Biologics", "A")));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1002")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));

        Map<String, Object> result = service.parseExcelByTaskTemplate(
                taskTemplateId,
                templateId,
                uploadWorkbook(
                        List.of("EmployeeId", "email", "Country", "companyName"),
                        List.of("E1002", "bob@example.org", "CN", "2000")));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows");
        Map<String, String> row = rows.get(0);
        assertEquals("bob@example.org", row.get("Email"));
        assertEquals("中国", row.get("Country"));
        assertEquals("无锡生物", row.get("CompanyName"));
        assertFalse(row.containsKey("email"));
        assertFalse(row.containsKey("companyName"));
    }

    @Test
    void generateExcelTemplateByTaskTemplate_formatsUploadColumnsAsText() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(templateId);
        variant.setTokensJson("[{\"key\":\"CustomNote\",\"label\":\"Custom Note\"}]");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of(
                resolvedBinding("AnniversaryDate", "Anniversary Date", "Manual", "BLOCK")));
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(variant));

        byte[] data = service.generateExcelTemplateByTaskTemplate(taskTemplateId, templateId);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("@", sheet.getColumnStyle(0).getDataFormatString());
            assertEquals("@", sheet.getColumnStyle(1).getDataFormatString());
            assertEquals("@", sheet.getColumnStyle(2).getDataFormatString());
        }
    }

    @Test
    void generateExcelTemplateByTaskTemplate_marksSystemFieldsAsOptionalOverrides() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(templateId);

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of(
                resolvedBinding("Name", "员工姓名", "System", "BLOCK"),
                resolvedBinding("Department", "部门", "System", "EMPTY"),
                resolvedBinding("birthdayWish", "生日祝福", "Manual", "BLOCK")));
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(variant));

        byte[] data = service.generateExcelTemplateByTaskTemplate(taskTemplateId, templateId);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("EmployeeId", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Name", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Department", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("birthdayWish", sheet.getRow(0).getCell(3).getStringCellValue());
            assertTrue(sheet.getRow(1).getCell(1).getStringCellValue().startsWith("可选"));
            assertTrue(sheet.getRow(1).getCell(1).getStringCellValue().contains("填写值优先"));
            assertTrue(sheet.getRow(1).getCell(2).getStringCellValue().startsWith("可选"));
            assertTrue(sheet.getRow(1).getCell(3).getStringCellValue().startsWith("必填"));
        }
    }

    @Test
    void parseExcelByTaskTemplate_fillsBlankSystemFieldsAndPreservesManualOverrides() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        List<TaskTemplateService.ResolvedBinding> bindings = List.of(
                resolvedBinding("Name", "员工姓名", "System", "BLOCK"),
                resolvedBinding("Department", "部门", "System", "EMPTY"),
                resolvedBinding("birthdayWish", "生日祝福", "Manual", "BLOCK"));
        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(bindings);
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "employeeId", "E1001",
                        "name", "系统姓名",
                        "department", "BIO")));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));

        Map<String, Object> result = service.parseExcelByTaskTemplate(
                taskTemplateId,
                templateId,
                uploadWorkbook(
                        List.of("EmployeeId", "Name", "Department", "birthdayWish"),
                        List.of("E1001", "人工姓名", "", "生日快乐")));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertEquals("人工姓名", rows.get(0).get("Name"));
        assertEquals("BIO", rows.get(0).get("Department"));
        assertEquals("生日快乐", rows.get(0).get("birthdayWish"));
    }

    @Test
    void parseExcelByTaskTemplate_treatsUntouchedInstructionCellsAsBlankWhenDataUsesInstructionRow() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant variant = new TemplateChannelVariant();
        variant.setId(templateId);

        List<TaskTemplateService.ResolvedBinding> bindings = List.of(
                resolvedBinding("Name", "员工姓名", "System", "BLOCK"),
                resolvedBinding("Department", "部门", "System", "EMPTY"),
                resolvedBinding("birthdayWish", "生日祝福", "Manual", "BLOCK"));
        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(bindings);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(variant));
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "employeeId", "E1001",
                        "name", "系统姓名",
                        "department", "BIO")));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));

        byte[] template = service.generateExcelTemplateByTaskTemplate(taskTemplateId, templateId);
        MockMultipartFile upload;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row instructionRow = workbook.getSheetAt(0).getRow(1);
            assertTrue(instructionRow.getCell(1).getStringCellValue().startsWith("可选 - "));
            assertTrue(instructionRow.getCell(2).getStringCellValue().startsWith("可选 - "));
            instructionRow.getCell(0).setCellValue("E1001");
            instructionRow.getCell(3).setCellValue("生日快乐");
            workbook.write(out);
            upload = new MockMultipartFile(
                    "file",
                    "upload.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }

        Map<String, Object> result = service.parseExcelByTaskTemplate(taskTemplateId, templateId, upload);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertEquals("系统姓名", rows.get(0).get("Name"));
        assertEquals("BIO", rows.get(0).get("Department"));
        assertEquals("生日快乐", rows.get(0).get("birthdayWish"));
    }

    @Test
    void generateExcelTemplateByTaskTemplate_prefillsOnlyWhenConditionRuleIsBound() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");
        taskTemplate.setConditionRuleVersionId(20L);
        SysUser employee = new SysUser();
        employee.setEmployeeId("E1001");
        employee.setName("Allowed User");
        employee.setEmail("allowed@example.org");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of());
        when(conditionRuleService.findMatchingEmployees(eq(20L), any())).thenReturn(List.of(employee));

        byte[] data = service.generateExcelTemplateByTaskTemplate(taskTemplateId, templateId);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("EmployeeId", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("E1001", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Allowed User", sheet.getRow(2).getCell(1).getStringCellValue());
        }
        verify(conditionRuleService).findMatchingEmployees(eq(20L), any());
    }

    @Test
    void parseExcelByTaskTemplate_preservesDateFormattedCellDisplayValue() throws Exception {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of(
                resolvedBinding("AnniversaryDate", "Anniversary Date", "Manual", "BLOCK")));
        when(odataService.fetchEmployeesByIds(List.of("E1003"))).thenReturn(Map.of(
                "E1003", Map.of("EmployeeId", "E1003")));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1003")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));

        Map<String, Object> result = service.parseExcelByTaskTemplate(
                taskTemplateId,
                templateId,
                uploadWorkbookWithDateFormattedCell());

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) result.get("rows");
        assertEquals("2022/1/12", rows.get(0).get("AnniversaryDate"));
    }

    @Test
    void previewTaskTemplateRow_resolvesEmailAndNameFromLowercaseMasterDataTokens() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{Name}} <{{Email}}>");

        Map<String, String> rowData = new LinkedHashMap<>();
        rowData.put("EmployeeId", "E1001");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001"))))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "employeeId", "E1001",
                        "name", "Alice",
                        "email", "alice.master@example.org")));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(templateCenterService.renderVariantContentForSend(eq(template), any())).thenReturn("Care message");
        when(templateCenterService.resolveVariantMessageType(template)).thenReturn("email_html");

        Map<String, Object> preview = service.previewTaskTemplateRow(taskTemplateId, templateId, rowData);

        assertEquals("alice.master@example.org", preview.get("recipient"));
        assertEquals("Alice", preview.get("employeeName"));
        assertEquals("Hello Alice <alice.master@example.org>", preview.get("subject"));
    }

    @Test
    void previewTaskTemplateRow_returnsDingTalkPreviewSurfaceAndRecipient() {
        when(dingTalkChannel.getType()).thenReturn("DingTalk");
        service = new SendService(
                odataService,
                connectionService,
                templateSenderMailboxService,
                taskTemplateService,
                templateCenterService,
                fieldRegistryService,
                recipientScopeService,
                conditionExpressionService,
                taskGovernanceService,
                runCenterService,
                integrationLogService,
                conditionRuleService,
                masterDataLookupService,
                sysUserMapper,
                List.of(dingTalkChannel));

        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setName("Care Task");
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("DingTalk");
        template.setMessageType("text");
        template.setSubject("Hi {{Name}}");

        Map<String, String> rowData = new LinkedHashMap<>();
        rowData.put("EmployeeId", "E1001");

        SysUser user = new SysUser();
        user.setDingtalkUserId("dt_alice");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001"))))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "employeeId", "E1001",
                        "name", "Alice")));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(templateCenterService.previewVariantForSend(eq("Care Task"), eq(template), any())).thenReturn(Map.of(
                "subject", "Hi Alice",
                "channel", "DingTalk",
                "messageType", "text",
                "previewType", "DINGTALK",
                "renderedPayload", Map.of("msgtype", "text", "text", Map.of("content", "Hi Alice")),
                "mobilePreview", Map.of("surface", "mobile", "card", Map.of("text", "Hi Alice")),
                "desktopPreview", Map.of("surface", "desktop", "card", Map.of("text", "Hi Alice"))));

        Map<String, Object> preview = service.previewTaskTemplateRow(taskTemplateId, templateId, rowData);

        assertEquals("dt_alice", preview.get("recipient"));
        assertEquals("Alice", preview.get("employeeName"));
        assertEquals("DINGTALK", preview.get("previewType"));
        assertTrue(preview.get("mobilePreview") instanceof Map<?, ?>);
    }

    @Test
    void confirmTaskTemplateSend_resolvesMissingEmailFromLowercaseMasterDataTokens() {
        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("Email");
        template.setSubject("Hello {{Name}} <{{Email}}>");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        List<Map<String, String>> rows = List.of(row("E1001", ""));

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of(
                "E1001", Map.of(
                        "employeeId", "E1001",
                        "name", "Alice",
                        "email", "alice.master@example.org")));
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("email_html");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(emailChannel).send(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("alice.master@example.org", requestCaptor.getValue().recipient());
        org.junit.jupiter.api.Assertions.assertEquals("Hello Alice <alice.master@example.org>", requestCaptor.getValue().subject());
        verify(runCenterService).markSentSuccess(item);
    }

    @Test
    void confirmTaskTemplateSend_usesDingTalkUserIdFromSysUserInsteadOfRowData() {
        when(dingTalkChannel.getType()).thenReturn("DingTalk");
        service = new SendService(
                odataService,
                connectionService,
                templateSenderMailboxService,
                taskTemplateService,
                templateCenterService,
                fieldRegistryService,
                recipientScopeService,
                conditionExpressionService,
                taskGovernanceService,
                runCenterService,
                integrationLogService,
                conditionRuleService,
                masterDataLookupService,
                sysUserMapper,
                List.of(dingTalkChannel));

        Long taskTemplateId = 7L;
        Long templateId = 11L;

        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(taskTemplateId);
        taskTemplate.setMode("Manual");

        TemplateChannelVariant template = new TemplateChannelVariant();
        template.setId(templateId);
        template.setStatus("Published");
        template.setChannel("DingTalk");
        template.setSubject("Hello {{EmployeeId}}");
        template.setContent("Care message");

        TaskRun run = new TaskRun();
        run.setId(123L);

        TaskRecipientItem item = new TaskRecipientItem();
        item.setId(456L);
        item.setTaskRunId(123L);
        item.setRecipientId("E1001");

        Map<String, String> row = row("E1001", "user@example.org");
        row.put("DingTalkUserId", "dt_from_upload");
        List<Map<String, String>> rows = List.of(row);

        SysUser user = new SysUser();
        user.setDingtalkUserId("dt_from_sys_user");

        when(taskTemplateService.getExecutableTemplate(taskTemplateId)).thenReturn(taskTemplate);
        when(taskTemplateService.listVariantsForTaskTemplate(taskTemplateId)).thenReturn(List.of(template));
        when(recipientScopeService.validateByEmployeeIds(eq(List.of("E1001")), eq(taskTemplateId)))
                .thenReturn(new RecipientScopeService.ScopeValidationResult(Set.of(), "{\"scope\":\"test\"}"));
        when(runCenterService.startRun(any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(run);
        when(taskGovernanceService.checkSendApprovalGate(run.getId()))
                .thenReturn(new TaskGovernanceService.ApprovalGateResult(false, List.of(), "APPROVED_READY"));
        when(taskTemplateService.getResolvedBindings(taskTemplateId, templateId)).thenReturn(List.of());
        when(odataService.fetchEmployeesByIds(List.of("E1001"))).thenReturn(Map.of());
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(runCenterService.createRecipientItem(any(), any(), any(), any())).thenReturn(item);
        when(templateCenterService.renderVariantContentForSend(any(), any())).thenReturn("Care message");
        when(templateCenterService.renderVariantChannelPayloadForSend(any(), any())).thenReturn(null);
        when(templateCenterService.resolveVariantMessageType(any())).thenReturn("markdown");

        service.confirmTaskTemplateSend(taskTemplateId, templateId, rows);

        org.mockito.ArgumentCaptor<MessageChannel.MessageRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(MessageChannel.MessageRequest.class);
        verify(dingTalkChannel).send(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("dt_from_sys_user", requestCaptor.getValue().recipient());
    }

    private Map<String, String> row(String employeeId, String email) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("EmployeeId", employeeId);
        row.put("Email", email);
        return row;
    }

    private ExternalConnectionService.ConnectionConfig activeSmtpConnection(Map<String, String> overrides) {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("host", "smtp.example.com");
        cfg.put("port", "465");
        cfg.put("username", "sender@example.com");
        cfg.put("password", "secret");
        cfg.put("useSsl", "true");
        cfg.put("fromAddress", "sender@example.com");
        cfg.put("fromName", "Recognition Platform");
        cfg.putAll(overrides);
        return new ExternalConnectionService.ConnectionConfig(
                99L,
                "SMTP",
                "Default SMTP",
                "Active",
                1,
                null,
                null,
                cfg);
    }

    private TemplateSenderMailboxService.Resolution activeMailboxResolution(Map<String, String> overrides) {
        Map<String, String> cfg = new LinkedHashMap<>(activeSmtpConnection(overrides).config());
        return new TemplateSenderMailboxService.Resolution(
                "ACTIVE_SMTP",
                null,
                99L,
                "Default SMTP",
                cfg.get("host"),
                cfg.get("port"),
                cfg.get("username"),
                cfg.get("fromAddress"),
                cfg.get("fromName"),
                null,
                cfg,
                Map.of("senderMailboxSource", "ACTIVE_SMTP", "externalConnectionId", "99"));
    }

    private TaskTemplateService.ResolvedBinding resolvedBinding(
            String code,
            String name,
            String sourceType,
            String missingPolicy) {
        FieldRegistry field = new FieldRegistry();
        field.setCode(code);
        field.setName(name);
        field.setSourceType(sourceType);
        field.setMissingPolicy(missingPolicy);
        field.setStatus("Active");

        TaskTemplateFieldBinding binding = new TaskTemplateFieldBinding();
        binding.setMissingPolicy(missingPolicy);
        binding.setRequiredFlag("Manual".equals(sourceType) ? 1 : 0);
        return new TaskTemplateService.ResolvedBinding(binding, field);
    }

    private MockMultipartFile uploadWorkbook(String header, String value) throws Exception {
        return uploadWorkbook(List.of(header), List.of(value));
    }

    private MockMultipartFile uploadWorkbook(List<String> headers, List<String> values) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("upload");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            Row dataRow = sheet.createRow(1);
            for (int i = 0; i < values.size(); i++) {
                dataRow.createCell(i).setCellValue(values.get(i));
            }
            workbook.write(out);
            return new MockMultipartFile(
                    "file",
                    "upload.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }

    private MockMultipartFile uploadWorkbookWithDateFormattedCell() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("upload");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("EmployeeId");
            headerRow.createCell(1).setCellValue("AnniversaryDate");

            org.apache.poi.ss.usermodel.CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy/m/d"));

            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue("E1003");
            org.apache.poi.ss.usermodel.Cell dateCell = dataRow.createCell(1);
            dateCell.setCellValue(java.time.LocalDate.of(2022, 1, 12));
            dateCell.setCellStyle(dateStyle);

            workbook.write(out);
            return new MockMultipartFile(
                    "file",
                    "upload.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
