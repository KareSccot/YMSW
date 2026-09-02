package com.wuxibio.care.service;

import com.wuxibio.care.entity.RoleTargetGroup;
import com.wuxibio.care.entity.ScopeAssignment;
import com.wuxibio.care.entity.SysRole;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.SysUserRole;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateShare;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.mapper.RoleTargetGroupMapper;
import com.wuxibio.care.mapper.ScopeAssignmentMapper;
import com.wuxibio.care.mapper.SysMenuMapper;
import com.wuxibio.care.mapper.SysRoleMapper;
import com.wuxibio.care.mapper.SysRoleMenuMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.SysUserRoleMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TaskTemplateShareMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import com.wuxibio.care.mapper.TemplateShareMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernanceServiceTest {

    @Mock private SysRoleMapper roleMapper;
    @Mock private SysRoleMenuMapper roleMenuMapper;
    @Mock private RoleTargetGroupMapper roleTargetGroupMapper;
    @Mock private ScopeAssignmentMapper scopeAssignmentMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private SysUserRoleMapper userRoleMapper;
    @Mock private SysMenuMapper menuMapper;
    @Mock private TemplateShareMapper templateShareMapper;
    @Mock private TaskTemplateShareMapper taskTemplateShareMapper;
    @Mock private TaskTemplateMapper taskTemplateMapper;
    @Mock private TemplateHeaderMapper templateHeaderMapper;
    @Mock private AuditLogService auditLogService;
    @Mock private FunctionPermissionService functionPermissionService;
    @Mock private PermissionChangeAuditService permissionChangeAuditService;
    @Mock private ConditionExpressionService conditionExpressionService;
    @Mock private TimeDependentService timeDependentService;

    private GovernanceService service;

    @BeforeEach
    void setUp() {
        service = new GovernanceService(
                roleMapper,
                roleMenuMapper,
                roleTargetGroupMapper,
                scopeAssignmentMapper,
                userMapper,
                userRoleMapper,
                menuMapper,
                templateShareMapper,
                taskTemplateShareMapper,
                taskTemplateMapper,
                templateHeaderMapper,
                auditLogService,
                functionPermissionService,
                permissionChangeAuditService,
                conditionExpressionService,
                timeDependentService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hasTaskTemplatePermission_returnsTrueForOwner() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(4L);
        taskTemplate.setOwnerUserId("2");

        when(taskTemplateMapper.selectById(4L)).thenReturn(taskTemplate);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(2L, 2L)));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "Process Specialist", 0)));

        boolean allowed = service.hasTaskTemplatePermission(4L, 2L, true);

        assertTrue(allowed);
        verify(taskTemplateShareMapper, never()).selectList(any());
    }

    @Test
    void hasTaskTemplatePermission_returnsTrueForUsernameOwner() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(4L);
        taskTemplate.setOwnerUserId("qa_operator");

        when(taskTemplateMapper.selectById(4L)).thenReturn(taskTemplate);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(2L, 2L)));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "Process Specialist", 0)));

        boolean allowed = service.hasTaskTemplatePermission(4L, 2L, "qa_operator", true);

        assertTrue(allowed);
        verify(taskTemplateShareMapper, never()).selectList(any());
    }

    @Test
    void hasTaskTemplatePermission_requiresEditShareForEditAction() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(4L);
        taskTemplate.setOwnerUserId("1");

        when(taskTemplateMapper.selectById(4L)).thenReturn(taskTemplate);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(2L, 2L)));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "Process Specialist", 0)));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "qa_operator"));
        when(taskTemplateShareMapper.selectList(any())).thenReturn(List.of());

        boolean allowed = service.hasTaskTemplatePermission(4L, 2L, true);

        assertFalse(allowed);
    }

    @Test
    void hasTaskTemplatePermission_allowsUseShareForReadAction() {
        TaskTemplate taskTemplate = new TaskTemplate();
        taskTemplate.setId(4L);
        taskTemplate.setOwnerUserId("1");

        when(taskTemplateMapper.selectById(4L)).thenReturn(taskTemplate);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(2L, 2L)));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(role(2L, "Process Specialist", 0)));
        when(userMapper.selectById(2L)).thenReturn(user(2L, "qa_operator"));
        when(taskTemplateShareMapper.selectList(any())).thenReturn(List.of(activeTaskTemplateShare("qa_operator", "Use")));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        boolean allowed = service.hasTaskTemplatePermission(4L, 2L, false);

        assertTrue(allowed);
    }

    @Test
    void listShares_rejectsTaskTemplateResource() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_GLOBAL_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThrows(BizException.class, () -> service.listShares("TASK_TEMPLATE", "4"));

        verify(templateShareMapper, never()).selectList(any());
        verify(taskTemplateShareMapper, never()).selectList(any());
    }

    @Test
    void listSharedTaskTemplateIds_readsDedicatedShareTable() {
        TaskTemplateShare share = new TaskTemplateShare();
        share.setTaskTemplateId(4L);
        share.setSharedToUserId("qa_operator");
        share.setPermissionLevel("Use");
        share.setStatus("Active");

        when(userMapper.selectById(2L)).thenReturn(user(2L, "qa_operator"));
        when(taskTemplateShareMapper.selectList(any())).thenReturn(List.of(share));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);

        assertEquals(List.of(4L), service.listSharedTaskTemplateIds(2L, false));
        verify(templateShareMapper, never()).selectList(any());
    }

    @Test
    void updateRoleDataScopes_replacesLegacyAndAssignmentScopes() {
        authenticate(1L, "qa_admin", "ROLE_GLOBAL_ADMIN");
        SysRole role = new SysRole();
        role.setId(2L);
        role.setName("Specialist");

        when(roleMapper.selectById(2L)).thenReturn(role);
        when(roleTargetGroupMapper.selectList(any())).thenReturn(List.of());
        when(scopeAssignmentMapper.selectList(any())).thenReturn(List.of());

        service.updateRoleDataScopes(2L, List.of(100L, 101L), List.of(9L, 10L));

        verify(roleTargetGroupMapper).delete(any());
        verify(roleTargetGroupMapper, times(2)).insert(any(RoleTargetGroup.class));
        verify(scopeAssignmentMapper, times(2)).delete(any());
        verify(scopeAssignmentMapper, times(2)).insert(any(ScopeAssignment.class));
        verify(permissionChangeAuditService).log(
                eq(2L),
                eq("ROLE_DATA_SCOPE_UPDATE"),
                eq("SYS_ROLE"),
                eq("2"),
                any(),
                any(),
                anyString());
    }

    private SysRole role(Long id, String name, Integer globalAdmin) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setName(name);
        role.setGlobalAdmin(globalAdmin);
        return role;
    }

    private SysUserRole userRole(Long userId, Long roleId) {
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setStatus("Active");
        return user;
    }

    private TaskTemplateShare activeTaskTemplateShare(String username, String permissionLevel) {
        TaskTemplateShare share = new TaskTemplateShare();
        share.setSharedToUserId(username);
        share.setPermissionLevel(permissionLevel);
        share.setStatus("Active");
        return share;
    }

    private void authenticate(Long userId, String username, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
