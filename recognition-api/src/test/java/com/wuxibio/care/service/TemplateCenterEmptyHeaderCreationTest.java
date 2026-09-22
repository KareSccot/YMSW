package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TemplateChannelVariant;
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

import java.time.LocalDate;
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

class TemplateCenterEmptyHeaderCreationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsDraftHeaderWithoutCreatingAChannelVariant() {
        Fixture fixture = fixture();
        authenticate(10L, "owner");
        when(fixture.headerMapper.selectCount(any())).thenReturn(0L);
        when(fixture.governanceService.hasTemplateHeaderPermissionById(51L, 10L, true)).thenReturn(true);
        doAnswer(invocation -> {
            TemplateHeader header = invocation.getArgument(0);
            header.setId(51L);
            return 1;
        }).when(fixture.headerMapper).insert(any(TemplateHeader.class));

        TemplateCenterService.TemplateHeaderView result = fixture.service.createEmptyHeader(
                "  Monthly Recognition  ", "TASK");

        ArgumentCaptor<TemplateHeader> headerCaptor = ArgumentCaptor.forClass(TemplateHeader.class);
        verify(fixture.headerMapper).insert(headerCaptor.capture());
        TemplateHeader inserted = headerCaptor.getValue();
        assertThat(inserted.getName()).isEqualTo("Monthly Recognition");
        assertThat(inserted.getTemplateKind()).isEqualTo("TASK");
        assertThat(inserted.getStatus()).isEqualTo("Draft");
        assertThat(inserted.getOwnerUserId()).isEqualTo("owner");
        assertThat(result.id()).isEqualTo("51");
        assertThat(result.status()).isEqualTo("Draft");
        assertThat(result.variants()).isEmpty();
        verify(fixture.variantMapper, never()).insert(any(TemplateChannelVariant.class));
        verify(fixture.auditLogService).log(
                "TEMPLATE_HEADER_CREATE",
                GovernanceService.RESOURCE_TEMPLATE_HEADER,
                "51",
                "name=Monthly Recognition, templateKind=TASK, variants=0");
    }

    @Test
    void rejectsANameThatIsEmptyAfterSanitizing() {
        Fixture fixture = fixture();
        authenticate(10L, "owner");

        assertThatThrownBy(() -> fixture.service.createEmptyHeader("<b></b>", "TASK"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模板组名称不能为空");

        verify(fixture.headerMapper, never()).insert(any(TemplateHeader.class));
    }

    private Fixture fixture() {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);

        when(timeDependentService.normalizeStart(null)).thenReturn(LocalDate.of(2026, 8, 25));
        when(timeDependentService.normalizeEnd(null)).thenReturn(LocalDate.of(9999, 12, 31));
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
                timeDependentService,
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                mock(TemplatePreviewService.class),
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class),
                mock(ApprovalWorkflowService.class),
                mock(TemplateSenderMailboxService.class));
        return new Fixture(service, headerMapper, variantMapper, governanceService, auditLogService);
    }

    private void authenticate(Long userId, String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record Fixture(
            TemplateCenterService service,
            TemplateHeaderMapper headerMapper,
            TemplateChannelVariantMapper variantMapper,
            GovernanceService governanceService,
            AuditLogService auditLogService) {
    }
}
