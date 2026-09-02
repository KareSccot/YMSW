package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ConditionRule;
import com.wuxibio.care.entity.ConditionRuleVersion;
import com.wuxibio.care.mapper.AutoTriggerDefMapper;
import com.wuxibio.care.mapper.ConditionRuleMapper;
import com.wuxibio.care.mapper.ConditionRuleVersionMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConditionRuleServiceOwnershipTest {

    @Mock private ConditionRuleMapper ruleMapper;
    @Mock private ConditionRuleVersionMapper versionMapper;
    @Mock private AutoTriggerDefMapper autoTriggerMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private MasterDataLookupService masterDataLookupService;
    @Mock private MasterDataReferenceService masterDataReferenceService;
    @Mock private MasterDataLabelService masterDataLabelService;
    @Mock private AuditLogService auditLogService;

    private ConditionRuleService service;

    @BeforeEach
    void setUp() {
        service = new ConditionRuleService(
                ruleMapper,
                versionMapper,
                autoTriggerMapper,
                userMapper,
                taskTemplateMapper,
                new ConditionExpressionService(),
                masterDataLookupService,
                masterDataReferenceService,
                masterDataLabelService,
                auditLogService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pageFiltersNonGlobalAdminByCreator() {
        authenticate(22L, "ROLE_2");
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        service.page(1, 20, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ConditionRule>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ruleMapper).selectList(captor.capture());
        assertThat(captor.getValue().getExpression().getNormal()).isNotEmpty();
    }

    @Test
    void detailRejectsAnotherUsersRule() {
        authenticate(22L, "ROLE_2");
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));

        assertThatThrownBy(() -> service.detail(10L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void publishedOptionsContainOnlyCurrentUsersRules() {
        authenticate(22L, "ROLE_2");
        ConditionRuleVersion ownVersion = version(101L, 10L);
        ConditionRuleVersion otherVersion = version(102L, 20L);
        when(versionMapper.selectList(any())).thenReturn(List.of(ownVersion, otherVersion));
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 22L));
        when(ruleMapper.selectById(20L)).thenReturn(rule(20L, 33L));

        List<ConditionRuleService.RuleVersionView> result = service.listPublished();

        assertThat(result).extracting(ConditionRuleService.RuleVersionView::ruleId)
                .containsExactly(10L);
    }

    @Test
    void accessiblePublishedVersionRejectsAnotherUsersRule() {
        authenticate(22L, "ROLE_2");
        when(versionMapper.selectById(101L)).thenReturn(version(101L, 10L));
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));

        assertThatThrownBy(() -> service.requireAccessiblePublishedVersion(101L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);
    }

    @Test
    void globalAdminCanAccessAnotherUsersPublishedRule() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(versionMapper.selectById(101L)).thenReturn(version(101L, 10L));
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));

        assertThat(service.requireAccessiblePublishedVersion(101L).ruleId()).isEqualTo(10L);
    }

    @Test
    void systemPublishedVersionLookupDoesNotRequireInteractiveOwner() {
        when(versionMapper.selectById(101L)).thenReturn(version(101L, 10L));
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));

        assertThat(service.requirePublishedVersion(101L).ruleId()).isEqualTo(10L);
    }

    @Test
    void deleteRemovesOwnedUnusedRuleAndItsVersions() {
        authenticate(22L, "ROLE_2");
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 22L));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(101L, 10L)));

        service.delete(10L);

        verify(versionMapper).delete(any());
        verify(ruleMapper).deleteById(10L);
        verify(auditLogService).log(
                "CONDITION_RULE_DELETE", "CONDITION_RULE", "10", "ruleCode=CR_10");
    }

    @Test
    void globalAdminCanDeleteAnotherUsersUnusedRule() {
        authenticate(1L, "ROLE_GLOBAL_ADMIN");
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));
        when(versionMapper.selectList(any())).thenReturn(List.of());

        service.delete(10L);

        verify(versionMapper).delete(any());
        verify(ruleMapper).deleteById(10L);
    }

    @Test
    void deleteRejectsRuleOwnedByAnotherUser() {
        authenticate(22L, "ROLE_2");
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 33L));

        assertThatThrownBy(() -> service.delete(10L))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 403);

        verify(versionMapper, never()).delete(any());
        verify(ruleMapper, never()).deleteById(10L);
    }

    @Test
    void deleteRejectsRuleThatIsStillInUse() {
        authenticate(22L, "ROLE_2");
        when(ruleMapper.selectById(10L)).thenReturn(rule(10L, 22L));
        when(versionMapper.selectList(any())).thenReturn(List.of(version(101L, 10L)));
        when(autoTriggerMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(10L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仍被 1 个 Task Template 或 Auto Trigger 使用");

        verify(versionMapper, never()).delete(any());
        verify(ruleMapper, never()).deleteById(10L);
    }

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of(() -> authority)));
    }

    private ConditionRule rule(Long id, Long createdBy) {
        ConditionRule rule = new ConditionRule();
        rule.setId(id);
        rule.setRuleCode("CR_" + id);
        rule.setRuleName("Rule " + id);
        rule.setStatus(ConditionRuleService.STATUS_ACTIVE);
        rule.setCreatedBy(createdBy);
        return rule;
    }

    private ConditionRuleVersion version(Long id, Long ruleId) {
        ConditionRuleVersion version = new ConditionRuleVersion();
        version.setId(id);
        version.setRuleId(ruleId);
        version.setVersionNo(1);
        version.setStatus(ConditionRuleService.VERSION_PUBLISHED);
        version.setExpressionJson("{\"field\":\"Country\",\"operator\":\"eq\",\"value\":\"CN\"}");
        version.setRequiredFieldsJson("[\"Country\"]");
        version.setPublishedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        return version;
    }
}
