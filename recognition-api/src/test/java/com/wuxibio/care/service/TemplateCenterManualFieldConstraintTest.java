package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateCenterManualFieldConstraintTest {

    @Test
    void updateVariant_rejectsManualTokenWhenHeaderBoundToAutoTaskTemplate() {
        Fixture fixture = newFixture(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                fixture.service,
                "ensureManualFieldsAllowedForAutoBoundHeader",
                100L,
                "Hi",
                "<p>{{Name}} {{AwardReason}}</p>",
                null,
                null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AwardReason");
    }

    @Test
    void updateVariant_rejectsUnusedCustomTokenWhenHeaderBoundToAutoTaskTemplate() {
        Fixture fixture = newFixture(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                fixture.service,
                "ensureManualFieldsAllowedForAutoBoundHeader",
                100L,
                "Hi",
                "<p>{{Name}}</p>",
                null,
                "[{\"key\":\"UnusedManual\",\"label\":\"Unused\"}]"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("UnusedManual");
    }

    @Test
    void updateVariant_allowsSystemTokensWhenHeaderBoundToAutoTaskTemplate() {
        Fixture fixture = newFixture(true);

        ReflectionTestUtils.invokeMethod(
                fixture.service,
                "ensureManualFieldsAllowedForAutoBoundHeader",
                100L,
                "Hi {{Name}}",
                "<p>{{Date}} {{EmployeeId}}</p>",
                null,
                null);
    }

    @Test
    void updateVariant_allowsManualTokenWhenHeaderNotBoundToAutoTaskTemplate() {
        Fixture fixture = newFixture(false);

        ReflectionTestUtils.invokeMethod(
                fixture.service,
                "ensureManualFieldsAllowedForAutoBoundHeader",
                100L,
                "Hi",
                "<p>{{AwardReason}}</p>",
                null,
                null);
    }

    private Fixture newFixture(boolean autoBound) {
        TemplateHeaderMapper headerMapper = mock(TemplateHeaderMapper.class);
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        GovernanceService governanceService = mock(GovernanceService.class);
        TimeDependentService timeDependentService = mock(TimeDependentService.class);
        TemplateRenderService renderService = new TemplateRenderService(tokenService);
        DingTalkPayloadService dingTalkPayloadService = new DingTalkPayloadService();
        TemplatePreviewService previewService = new TemplatePreviewService(renderService, dingTalkPayloadService);

        when(tokenService.getSystemTokens()).thenReturn(List.of(
                new TemplateTokenService.BuiltinToken("Name", "姓名", "")
        ));
        when(taskTemplateMapper.selectCount(any())).thenReturn(autoBound ? 1L : 0L);

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
                timeDependentService,
                dingTalkPayloadService,
                renderService,
                previewService,
                mock(TemplateTestSendService.class),
                mock(com.wuxibio.care.channel.EmailChannel.class));
        return new Fixture(service);
    }

    private record Fixture(TemplateCenterService service) {
    }
}
