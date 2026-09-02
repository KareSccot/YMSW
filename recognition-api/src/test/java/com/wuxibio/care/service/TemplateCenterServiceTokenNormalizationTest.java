package com.wuxibio.care.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateTestSendLogMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateCenterServiceTokenNormalizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeTokensJsonDropsSystemFieldMappingTokens() throws Exception {
        TemplateTokenService tokenService = mock(TemplateTokenService.class);
        when(tokenService.getSystemTokens()).thenReturn(List.of(
                new TemplateTokenService.BuiltinToken("Name", "员工姓名", ""),
                new TemplateTokenService.BuiltinToken("Department", "部门", "")
        ));
        TemplateCenterService service = newService(tokenService);

        String normalized = callNormalizeTokensJson(service, """
                [
                  {"key":"Name","label":"员工姓名","previewValue":"Ada"},
                  {"key":"AwardName","label":"奖项","previewValue":"金奖"},
                  {"key":"Department","label":"部门","previewValue":"Ops"}
                ]
                """);

        JsonNode root = objectMapper.readTree(normalized);
        assertThat(root).hasSize(1);
        assertThat(root.get(0).path("key").asText()).isEqualTo("AwardName");
    }

    private String callNormalizeTokensJson(TemplateCenterService service, String tokensJson) throws Exception {
        Method method = TemplateCenterService.class.getDeclaredMethod("normalizeTokensJson", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, tokensJson);
    }

    private TemplateCenterService newService(TemplateTokenService tokenService) {
        TemplateRenderService renderService = new TemplateRenderService(tokenService);
        DingTalkPayloadService dingTalkPayloadService = new DingTalkPayloadService();
        TemplatePreviewService previewService = new TemplatePreviewService(renderService, dingTalkPayloadService);
        return new TemplateCenterService(
                mock(TemplateHeaderMapper.class),
                mock(TemplateChannelVariantMapper.class),
                mock(TaskTemplateMapper.class),
                mock(SysUserMapper.class),
                mock(TemplateTestSendLogMapper.class),
                tokenService,
                new TemplateManualFieldService(tokenService),
                mock(GovernanceService.class),
                mock(AuditLogService.class),
                mock(TimeDependentService.class),
                dingTalkPayloadService,
                renderService,
                previewService,
                mock(TemplateTestSendService.class),
                mock(com.wuxibio.care.channel.EmailChannel.class));
    }
}
