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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterHeaderRenameTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void renameUpdatesOnlyTheHeaderNameForAnAuthorizedEditor() {
        Fixture fixture = fixture();
        authenticate(10L, "owner");
        when(fixture.governanceService.hasTemplateHeaderPermissionById(
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(10L),
                anyBoolean())).thenReturn(true);
        when(fixture.headerMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            TemplateHeader update = invocation.getArgument(0);
            fixture.header.setName(update.getName());
            return 1;
        }).when(fixture.headerMapper).updateById(any(TemplateHeader.class));

        TemplateCenterService.TemplateHeaderView result = fixture.service.updateHeaderName(
                "50", "  Updated Recognition  ");

        assertThat(result.name()).isEqualTo("Updated Recognition");
        ArgumentCaptor<TemplateHeader> updateCaptor = ArgumentCaptor.forClass(TemplateHeader.class);
        verify(fixture.headerMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getId()).isEqualTo(50L);
        assertThat(updateCaptor.getValue().getName()).isEqualTo("Updated Recognition");
        verify(fixture.auditLogService).log(
                "TEMPLATE_HEADER_RENAME",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                "50",
                "oldName=Recognition Template, newName=Updated Recognition");
    }

    @Test
    void renameRejectsADuplicateHeaderName() {
        Fixture fixture = fixture();
        authenticate(10L, "owner");
        when(fixture.governanceService.hasTemplateHeaderPermissionById(
                org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(10L),
                anyBoolean())).thenReturn(true);
        when(fixture.headerMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> fixture.service.updateHeaderName("50", "Existing Name"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("名称已存在");

        verify(fixture.headerMapper, never()).updateById(any(TemplateHeader.class));
    }

    @Test
    void renameRejectsAReadOnlyUser() {
        Fixture fixture = fixture();
        authenticate(11L, "reader");
        when(fixture.governanceService.hasTemplateHeaderPermissionById(50L, 11L, true)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.updateHeaderName("50", "Updated Recognition"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");

        verify(fixture.headerMapper, never()).updateById(any(TemplateHeader.class));
    }

    private Fixture fixture() {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setName("Recognition Template");
        header.setTemplateKind("TASK");
        header.setStatus("Published");
        header.setOwnerUserId("owner");

        when(headerMapper.selectById(50L)).thenReturn(header);
        when(variantMapper.selectList(any())).thenReturn(List.of());
        when(taskTemplateMapper.selectCount(any())).thenReturn(0L);
        when(governanceService.listSharedTemplateHeaderIds(any(), anyBoolean())).thenReturn(List.of());

        TemplateCenterService service = new TemplateCenterService(
                headerMapper,
                variantMapper,
                taskTemplateMapper,
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                new TemplateManualFieldService(tokenService),
                governanceService,
                auditLogService,
                mock(TimeDependentService.class),
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                mock(TemplatePreviewService.class),
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class),
                mock(ApprovalWorkflowService.class),
                mock(TemplateSenderMailboxService.class));
        return new Fixture(service, headerMapper, governanceService, auditLogService, header);
    }

    private void authenticate(Long userId, String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record Fixture(
            TemplateCenterService service,
            TemplateHeaderMapper headerMapper,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            TemplateHeader header) {
    }
}
