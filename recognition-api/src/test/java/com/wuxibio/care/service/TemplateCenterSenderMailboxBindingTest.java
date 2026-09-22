package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterSenderMailboxBindingTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateSenderMailbox_routesTaskOwnerToApprovalRequestApi() {
        Fixture fixture = fixture("owner");
        authenticate(10L, "owner");
        when(fixture.governanceService.hasTemplateHeaderPermissionById(50L, 10L, false)).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.updateSenderMailbox("50", 22L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发件箱使用申请");

        verify(fixture.templateSenderMailboxService, never()).requireBindableSenderMailbox(any());
        verify(fixture.templateHeaderMapper, never()).update(any(), any());
    }

    @Test
    void updateSenderMailbox_rejectsEditShareThatIsNotOwner() {
        Fixture fixture = fixture("owner");
        authenticate(11L, "shared-editor");

        assertThatThrownBy(() -> fixture.service.updateSenderMailbox("50", 22L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅模板组 Owner");

        verify(fixture.templateSenderMailboxService, never()).requireBindableSenderMailbox(any());
        verify(fixture.templateHeaderMapper, never()).update(any(), any());
    }

    @Test
    void updateSenderMailbox_routesGlobalAdminTaskChangeToApprovalRequestApi() {
        Fixture fixture = fixture("owner");
        authenticateGlobalAdmin(1L, "global.admin");

        assertThatThrownBy(() -> fixture.service.updateSenderMailbox("50", 22L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发件箱使用申请");

        verify(fixture.templateSenderMailboxService, never()).requireBindableSenderMailbox(any());
        verify(fixture.templateHeaderMapper, never()).update(any(), any());
    }

    @Test
    void updateSenderMailbox_rejectsClearingTaskTemplateBinding() {
        Fixture fixture = fixture("owner");
        authenticate(10L, "owner");

        assertThatThrownBy(() -> fixture.service.updateSenderMailbox("50", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发件箱使用申请");

        verify(fixture.templateSenderMailboxService, never()).requireBindableSenderMailbox(any());
        verify(fixture.templateHeaderMapper, never()).update(any(), any());
    }

    @Test
    void updateSenderMailbox_allowsMailboxBindingSystemNotificationTemplate() {
        Fixture fixture = fixture(
                "owner",
                TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION,
                TemplateCenterService.TEMPLATE_CODE_MAILBOX_BINDING_NOTIFICATION,
                null);
        authenticateGlobalAdmin(1L, "global.admin");

        assertThatCode(() -> fixture.service.updateSenderMailbox("50", 22L))
                .doesNotThrowAnyException();

        verify(fixture.templateSenderMailboxService).requireBindableSenderMailbox(22L);
        verify(fixture.templateHeaderMapper).update(any(), any());
    }

    @Test
    void headerViewSeparatesSystemTemplateIdentityFromActiveSmtpSelection() {
        Fixture activeFixture = fixture(
                "owner",
                TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION,
                TemplateCenterService.TEMPLATE_CODE_MAILBOX_BINDING_NOTIFICATION,
                null);
        authenticateGlobalAdmin(1L, "global.admin");

        TemplateCenterService.TemplateHeaderView activeView = activeFixture.service.getHeader("50");

        assertThat(activeView.mailboxBindingNotificationTemplate()).isTrue();
        assertThat(activeView.usesActiveSmtp()).isTrue();

        Fixture mailboxFixture = fixture(
                "owner",
                TemplateCenterService.TEMPLATE_KIND_WORKFLOW_NOTIFICATION,
                TemplateCenterService.TEMPLATE_CODE_MAILBOX_BINDING_NOTIFICATION,
                22L);

        TemplateCenterService.TemplateHeaderView mailboxView = mailboxFixture.service.getHeader("50");

        assertThat(mailboxView.mailboxBindingNotificationTemplate()).isTrue();
        assertThat(mailboxView.usesActiveSmtp()).isFalse();
    }

    private Fixture fixture(String ownerUsername) {
        return fixture(ownerUsername, TemplateCenterService.TEMPLATE_KIND_TASK, null, null);
    }

    private Fixture fixture(
            String ownerUsername,
            String templateKind,
            String templateCode,
            Long senderMailboxId) {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        TemplateSenderMailboxService senderMailboxService = mock(TemplateSenderMailboxService.class);
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        TemplateManualFieldService manualFieldService = new TemplateManualFieldService(tokenService);
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setCode(templateCode);
        header.setName("Recognition Template");
        header.setTemplateKind(templateKind);
        header.setSenderMailboxId(senderMailboxId);
        header.setStatus("Published");
        header.setOwnerUserId(ownerUsername);

        when(headerMapper.selectById(50L)).thenReturn(header);
        when(variantMapper.selectList(any())).thenReturn(List.of());
        when(taskTemplateMapper.selectCount(any())).thenReturn(0L);

        TemplateCenterService service = new TemplateCenterService(
                headerMapper,
                variantMapper,
                taskTemplateMapper,
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                manualFieldService,
                governanceService,
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                mock(TemplatePreviewService.class),
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class),
                mock(ApprovalWorkflowService.class),
                senderMailboxService);
        return new Fixture(service, headerMapper, governanceService, senderMailboxService);
    }

    private void authenticate(Long userId, String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateGlobalAdmin(Long userId, String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(() -> "ROLE_GLOBAL_ADMIN"));
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record Fixture(
            TemplateCenterService service,
            TemplateHeaderMapper templateHeaderMapper,
            GovernanceService governanceService,
            TemplateSenderMailboxService templateSenderMailboxService) {
    }
}
