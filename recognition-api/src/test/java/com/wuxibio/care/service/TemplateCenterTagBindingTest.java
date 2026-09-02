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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateCenterTagBindingTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanReplaceTemplateTags() {
        Fixture fixture = fixture("owner.user");
        authenticate(7L, "owner.user");
        when(fixture.templateTagService.listTagCodes(50L)).thenReturn(List.of("HR_SENSITIVE"));

        TemplateCenterService.TemplateHeaderView result = fixture.service.updateTemplateTags(
                "50", List.of("HR_SENSITIVE"));

        verify(fixture.templateTagService).replaceTags(50L, List.of("HR_SENSITIVE"));
        assertThat(result.tagCodes()).containsExactly("HR_SENSITIVE");
    }

    @Test
    void nonOwnerCannotReplaceTemplateTagsEvenWithTemplateManage() {
        Fixture fixture = fixture("owner.user");
        authenticate(8L, "editor.user");

        assertThatThrownBy(() -> fixture.service.updateTemplateTags("50", List.of("HR_SENSITIVE")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅模板组 Owner");

        verify(fixture.templateTagService, never()).replaceTags(any(), any());
    }

    @Test
    void globalAdminCanReplaceTagsForTemplateOwnedByAnotherUser() {
        Fixture fixture = fixture("owner.user");
        authenticateGlobalAdmin(1L, "global.admin");
        when(fixture.templateTagService.listTagCodes(50L)).thenReturn(List.of("HR_SENSITIVE"));

        TemplateCenterService.TemplateHeaderView result = fixture.service.updateTemplateTags(
                "50", List.of("HR_SENSITIVE"));

        verify(fixture.templateTagService).replaceTags(50L, List.of("HR_SENSITIVE"));
        assertThat(result.tagCodes()).containsExactly("HR_SENSITIVE");
    }

    private Fixture fixture(String ownerUsername) {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        TemplateTagService templateTagService = mock(TemplateTagService.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setName("Recognition Template");
        header.setTemplateKind("TASK");
        header.setStatus("Published");
        header.setOwnerUserId(ownerUsername);

        when(headerMapper.selectById(50L)).thenReturn(header);
        when(variantMapper.selectList(any())).thenReturn(List.of());
        when(taskTemplateMapper.selectCount(any())).thenReturn(0L);
        when(governanceService.hasTemplateHeaderPermissionById(
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);

        TemplateCenterService service = new TemplateCenterService(
                headerMapper,
                variantMapper,
                taskTemplateMapper,
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                new TemplateManualFieldService(tokenService),
                governanceService,
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                mock(TemplatePreviewService.class),
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class),
                mock(ApprovalWorkflowService.class),
                mock(TemplateSenderMailboxService.class),
                templateTagService);
        return new Fixture(service, templateTagService);
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

    private record Fixture(TemplateCenterService service, TemplateTagService templateTagService) {
    }
}
