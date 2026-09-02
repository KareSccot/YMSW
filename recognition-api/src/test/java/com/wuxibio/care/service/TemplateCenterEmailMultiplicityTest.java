package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TemplateCenterEmailMultiplicityTest {

    @Test
    void emailVariantsDoNotUseTheSingleMessageTypeUniquenessCheck() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        TemplateCenterService service = new TemplateCenterService(
                mock(TemplateHeaderMapper.class),
                variantMapper,
                mock(TaskTemplateMapper.class),
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                mock(TemplateTokenService.class),
                mock(TemplateManualFieldService.class),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                mock(DingTalkPayloadService.class),
                mock(TemplateRenderService.class),
                mock(TemplatePreviewService.class),
                mock(TemplateTestSendService.class),
                mock(EmailChannel.class));

        ReflectionTestUtils.invokeMethod(service, "ensureVariantUnique", 50L, "Email", "email_html", null);

        verify(variantMapper, never()).selectCount(any());
    }
}
