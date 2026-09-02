package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateShare;
import com.wuxibio.care.mapper.FieldRegistryMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateFieldBindingMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskTemplateServiceShareTest {

    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TaskTemplateShareMapper taskTemplateShareMapper;
    @Mock private TaskTemplateFieldBindingMapper bindingMapper;
    @Mock private FieldRegistryMapper fieldRegistryMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private TemplateChannelVariantMapper variantMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private ConditionRuleService conditionRuleService;
    @Mock private GovernanceService governanceService;
    @Mock private AuditLogService auditLogService;
    @Mock private OdataService odataService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private TemplateManualFieldService templateManualFieldService;

    private TaskTemplateService service;

    @BeforeEach
    void setUp() {
        service = new TaskTemplateService(
                taskTemplateMapper,
                taskTemplateShareMapper,
                bindingMapper,
                fieldRegistryMapper,
                templateHeaderMapper,
                variantMapper,
                sysUserMapper,
                conditionRuleService,
                governanceService,
                auditLogService,
                odataService,
                timeDependentService,
                templateManualFieldService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanGrantTaskTemplateShareWithUsernameRefs() {
        authenticate(10L, "owner_user");
        TaskTemplate template = taskTemplate(7L, "owner_user");
        SysUser owner = user(10L, "owner_user");
        SysUser target = user(20L, "target_user");

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(sysUserMapper.selectOne(any())).thenReturn(owner);
        when(sysUserMapper.selectById(20L)).thenReturn(target);
        when(taskTemplateShareMapper.selectOne(any())).thenReturn(null);
        when(timeDependentService.normalizeStart(null)).thenReturn(LocalDate.of(1970, 1, 1));
        when(timeDependentService.normalizeEnd(null)).thenReturn(LocalDate.of(9999, 12, 31));

        service.grantOrUpdateTaskTemplateShare(7L, 20L, "Edit");

        verify(taskTemplateShareMapper).insert(argThat((TaskTemplateShare row) ->
                Long.valueOf(7L).equals(row.getTaskTemplateId())
                        && "owner_user".equals(row.getOwnerUserId())
                        && "target_user".equals(row.getSharedToUserId())
                        && "Edit".equals(row.getPermissionLevel())
                        && "Active".equals(row.getStatus())));
    }

    @Test
    void nonOwnerCannotManageTaskTemplateShare() {
        authenticate(10L, "viewer_user");
        TaskTemplate template = taskTemplate(7L, "owner_user");
        SysUser owner = user(99L, "owner_user");

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(sysUserMapper.selectOne(any())).thenReturn(owner);

        assertThrows(BizException.class, () -> service.grantOrUpdateTaskTemplateShare(7L, 20L, "Use"));
        verify(taskTemplateShareMapper, never()).insert(any(TaskTemplateShare.class));
    }

    @Test
    void sharedUseCanExecuteActiveTaskTemplate() {
        authenticate(30010499L, "30010499");
        TaskTemplate template = taskTemplate(7L, "10042862");
        template.setStatus("Active");

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.hasTaskTemplatePermission(7L, 30010499L, "30010499", false))
                .thenReturn(true);

        assertSame(template, service.getExecutableTemplate(7L));
    }

    @Test
    void sharedUseCanPreviewTaskTemplatesBoundConditionRule() {
        authenticate(30010499L, "30010499");
        TaskTemplate template = taskTemplate(7L, "10042862");
        template.setConditionRuleVersionId(50L);
        Map<String, Object> preview = Map.of("matchedCount", 1);

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.hasTaskTemplatePermission(7L, 30010499L, "30010499", false))
                .thenReturn(true);
        when(conditionRuleService.previewPublishedAudienceForTaskTemplate(50L, null, 8))
                .thenReturn(preview);

        assertSame(preview, service.previewAudience(7L, null, 8));
        verify(conditionRuleService, never()).previewPublishedAudience(50L, null, 8);
    }

    @Test
    void sharedUseCannotMutateTaskTemplate() {
        authenticate(30010499L, "30010499");
        TaskTemplate template = taskTemplate(7L, "10042862");
        template.setStatus("Active");

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.hasTaskTemplatePermission(7L, 30010499L, "30010499", true))
                .thenReturn(false);

        assertThrows(BizException.class, () -> service.delete(7L));
        verify(taskTemplateMapper, never()).deleteById(7L);
    }

    @Test
    void unsharedUserCannotExecuteTaskTemplate() {
        authenticate(30010499L, "30010499");
        TaskTemplate template = taskTemplate(7L, "10042862");
        template.setStatus("Active");

        when(taskTemplateMapper.selectById(7L)).thenReturn(template);
        when(governanceService.hasTaskTemplatePermission(7L, 30010499L, "30010499", false))
                .thenReturn(false);

        assertThrows(BizException.class, () -> service.getExecutableTemplate(7L));
    }

    private TaskTemplate taskTemplate(Long id, String ownerUserId) {
        TaskTemplate template = new TaskTemplate();
        template.setId(id);
        template.setOwnerUserId(ownerUserId);
        return template;
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setName(username);
        user.setStatus("Active");
        return user;
    }

    private void authenticate(Long userId, String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(() -> "ROLE_USER"));
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
