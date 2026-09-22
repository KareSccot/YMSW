package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SenderMailbox;
import com.wuxibio.care.entity.SenderMailboxBindingAuthorization;
import com.wuxibio.care.entity.SenderMailboxBindingRequest;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SenderMailboxBindingAuthorizationMapper;
import com.wuxibio.care.mapper.SenderMailboxBindingRequestMapper;
import com.wuxibio.care.mapper.SenderMailboxMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SenderMailboxBindingApprovalServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                SenderMailboxBindingRequest.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                SenderMailboxBindingAuthorization.class);
    }

    @Mock SenderMailboxBindingRequestMapper requestMapper;
    @Mock SenderMailboxBindingAuthorizationMapper authorizationMapper;
    @Mock TemplateHeaderMapper templateHeaderMapper;
    @Mock SenderMailboxMapper mailboxMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock SenderMailboxService senderMailboxService;
    @Mock MailboxOwnerResolver ownerResolver;
    @Mock GovernanceService governanceService;
    @Mock AuditLogService auditLogService;
    @Mock ApplicationEventPublisher eventPublisher;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestFreezesMailboxOwnerAndDoesNotBindBeforeApproval() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Requester");
        SysUser owner = user(20L, "E20", "Mailbox Owner");
        TemplateHeader header = header("E10");
        SenderMailbox mailbox = mailbox("E20");
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenReturn(mailbox);
        when(ownerResolver.requireByEmployeeId("E20")).thenReturn(owner);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        doAnswer(invocation -> {
            SenderMailboxBindingRequest row = invocation.getArgument(0);
            row.setId(900L);
            return 1;
        }).when(requestMapper).insert(any(SenderMailboxBindingRequest.class));

        SenderMailboxBindingApprovalService.BindingActionResult result = service().request(50L, 70L, null);

        assertThat(result.outcome()).isEqualTo(SenderMailboxBindingApprovalService.OUTCOME_PENDING);
        assertThat(result.request().request().getStatus()).isEqualTo(SenderMailboxBindingApprovalService.PENDING);
        assertThat(result.request().request().getApproverUserId()).isEqualTo(20L);
        assertThat(result.request().request().getApproverEmployeeId()).isEqualTo("E20");
        verify(templateHeaderMapper, never()).updateById(any(TemplateHeader.class));
        verify(eventPublisher).publishEvent(new SenderMailboxBindingNotificationRequested(
                900L, SenderMailboxBindingNotificationService.EVENT_REQUESTED));
    }

    @Test
    void requestRejectsWhenTemplateAlreadyHasPendingApproval() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Requester");
        TemplateHeader header = header("E10");
        SenderMailboxBindingRequest pending = pendingRequest();
        pending.setMailboxNameSnapshot("Pending Mailbox");
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(71L)).thenReturn(mailbox(71L, "E30"));
        when(requestMapper.selectOne(any())).thenReturn(pending);

        assertThatThrownBy(() -> service().request(50L, 71L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已有待审批")
                .hasMessageContaining("Pending Mailbox");

        verify(requestMapper, never()).insert(any(SenderMailboxBindingRequest.class));
        verify(templateHeaderMapper, never()).updateById(any(TemplateHeader.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void previouslyAuthorizedMailboxStillCreatesFreshApprovalRequest() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Requester");
        SysUser owner = user(20L, "E20", "Mailbox Owner");
        TemplateHeader header = header("E10");
        header.setSenderMailboxId(71L);
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenReturn(mailbox("E20"));
        when(ownerResolver.requireByEmployeeId("E20")).thenReturn(owner);
        doAnswer(invocation -> {
            SenderMailboxBindingRequest row = invocation.getArgument(0);
            row.setId(901L);
            return 1;
        }).when(requestMapper).insert(any(SenderMailboxBindingRequest.class));

        SenderMailboxBindingApprovalService.BindingActionResult result = service().request(50L, 70L, null);

        assertThat(result.outcome()).isEqualTo(SenderMailboxBindingApprovalService.OUTCOME_PENDING);
        assertThat(result.effectiveSenderMailboxId()).isEqualTo(71L);
        assertThat(result.request().request().getPreviousSenderMailboxId()).isEqualTo(71L);
        assertThat(result.request().request().getRequestedSenderMailboxId()).isEqualTo(70L);
        assertThat(result.request().request().getApproverUserId()).isEqualTo(20L);
        verify(templateHeaderMapper, never()).updateById(any(TemplateHeader.class));
        verify(authorizationMapper, never()).selectCount(any());
        verify(requestMapper).insert(any(SenderMailboxBindingRequest.class));
        verify(eventPublisher).publishEvent(new SenderMailboxBindingNotificationRequested(
                901L, SenderMailboxBindingNotificationService.EVENT_REQUESTED));
    }

    @Test
    void mailboxOwnerBindsDirectlyAndCreatesAuthorizationButNoApprovalRecordOrNotification() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Mailbox Owner");
        TemplateHeader header = header("E10");
        SenderMailbox mailbox = mailbox("E10");
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenReturn(mailbox);
        when(ownerResolver.requireByEmployeeId("E10")).thenReturn(requester);
        when(authorizationMapper.selectCount(any())).thenReturn(0L);

        SenderMailboxBindingApprovalService.BindingActionResult result = service().request(50L, 70L, null);

        assertThat(result.outcome()).isEqualTo(SenderMailboxBindingApprovalService.OUTCOME_BOUND_OWNER);
        assertThat(result.request()).isNull();
        ArgumentCaptor<SenderMailboxBindingAuthorization> authorizationCaptor =
                ArgumentCaptor.forClass(SenderMailboxBindingAuthorization.class);
        verify(authorizationMapper).insert(authorizationCaptor.capture());
        assertThat(authorizationCaptor.getValue().getTemplateHeaderId()).isEqualTo(50L);
        assertThat(authorizationCaptor.getValue().getSenderMailboxId()).isEqualTo(70L);
        assertThat(authorizationCaptor.getValue().getAuthorizationSource()).isEqualTo("OWNER_DIRECT");
        verify(templateHeaderMapper).updateById(any(TemplateHeader.class));
        verify(requestMapper, never()).insert(any(SenderMailboxBindingRequest.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void selectingCurrentMailboxIsNoOpWithoutApprovalOrAuthorizationWrite() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Requester");
        TemplateHeader header = header("E10");
        header.setSenderMailboxId(70L);
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenReturn(mailbox("E20"));

        SenderMailboxBindingApprovalService.BindingActionResult result = service().request(50L, 70L, null);

        assertThat(result.outcome()).isEqualTo(SenderMailboxBindingApprovalService.OUTCOME_NO_CHANGE);
        verify(authorizationMapper, never()).selectCount(any());
        verify(authorizationMapper, never()).insert(any(SenderMailboxBindingAuthorization.class));
        verify(requestMapper, never()).insert(any(SenderMailboxBindingRequest.class));
        verify(templateHeaderMapper, never()).updateById(any(TemplateHeader.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void bindingStateSeparatesPendingAuthorizedAndOwnerMailboxes() {
        authenticate(10L, false);
        SysUser requester = user(10L, "E10", "Requester");
        TemplateHeader header = header("E10");
        header.setSenderMailboxId(70L);
        SenderMailboxBindingRequest pending = pendingRequest();
        SenderMailboxBindingAuthorization authorization = new SenderMailboxBindingAuthorization();
        authorization.setSenderMailboxId(71L);
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(requestMapper.selectOne(any())).thenReturn(pending);
        when(authorizationMapper.selectList(any())).thenReturn(List.of(authorization));
        when(senderMailboxService.listAvailableMailboxes()).thenReturn(List.of(
                mailbox(70L, "E20"),
                mailbox(71L, "E30"),
                mailbox(72L, "E10")));

        SenderMailboxBindingApprovalService.BindingState state = service().bindingState(50L);

        assertThat(state.currentSenderMailboxId()).isEqualTo(70L);
        assertThat(state.pendingRequest().request().getId()).isEqualTo(900L);
        assertThat(state.authorizedMailboxIds()).containsExactly(71L);
        assertThat(state.ownerMailboxIds()).containsExactly(72L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void assignedApprovalPageIsPendingOnlyAndExcludesLegacyOwnerSelfRecords() {
        authenticate(10L, false);
        when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "E10", "Requester"));
        when(requestMapper.selectList(any())).thenReturn(List.of());

        service().page(1, 20, "assigned", SenderMailboxBindingApprovalService.CANCELLED);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(requestMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<SenderMailboxBindingRequest> wrapper =
                (LambdaQueryWrapper<SenderMailboxBindingRequest>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("decision_source");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("OWNER_SELF");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(SenderMailboxBindingApprovalService.PENDING);
        assertThat(wrapper.getParamNameValuePairs()).doesNotContainValue(SenderMailboxBindingApprovalService.CANCELLED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void createdApprovalPageRetainsTerminalStatusFiltering() {
        authenticate(10L, false);
        when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "E10", "Requester"));
        when(requestMapper.selectList(any())).thenReturn(List.of());

        service().page(1, 20, "created", SenderMailboxBindingApprovalService.CANCELLED);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(requestMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<SenderMailboxBindingRequest> wrapper =
                (LambdaQueryWrapper<SenderMailboxBindingRequest>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("requester_user_id").contains("status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(10L);
        assertThat(wrapper.getParamNameValuePairs()).containsValue(SenderMailboxBindingApprovalService.CANCELLED);
    }

    @Test
    void allApprovalScopeIsNotSupported() {
        authenticate(10L, false);
        when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "E10", "Requester"));

        assertThatThrownBy(() -> service().page(1, 20, "all", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("assigned/created");

        verify(requestMapper, never()).selectList(any());
    }

    @Test
    void mailboxOwnerHasModuleAccessWithoutRolePermission() {
        when(sysUserMapper.selectById(20L)).thenReturn(user(20L, "E20", "Mailbox Owner"));
        when(mailboxMapper.selectCount(any())).thenReturn(1L);

        assertThat(service().hasMailboxModuleAccess(20L)).isTrue();
    }

    @Test
    void unrelatedUserHasNoMailboxModuleAccess() {
        when(sysUserMapper.selectById(30L)).thenReturn(user(30L, "E30", "Unrelated User"));
        when(mailboxMapper.selectCount(any())).thenReturn(0L);
        when(requestMapper.selectCount(any())).thenReturn(0L);

        assertThat(service().hasMailboxModuleAccess(30L)).isFalse();
    }

    @Test
    void unrelatedUserCannotReadMailboxBindingRequest() {
        authenticate(30L, false);
        when(sysUserMapper.selectById(30L)).thenReturn(user(30L, "E30", "Unrelated User"));
        when(requestMapper.selectById(900L)).thenReturn(pendingRequest());

        assertThatThrownBy(() -> service().get(900L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void globalAdminCannotDecideForFrozenOwner() {
        authenticate(1L, true);
        when(sysUserMapper.selectById(1L)).thenReturn(user(1L, "ADMIN", "Admin"));
        SenderMailboxBindingRequest pending = new SenderMailboxBindingRequest();
        pending.setId(900L);
        pending.setStatus(SenderMailboxBindingApprovalService.PENDING);
        pending.setApproverUserId(20L);
        when(requestMapper.selectById(900L)).thenReturn(pending);

        assertThatThrownBy(() -> service().decide(900L, "APPROVE", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Global Admin 不能代批");

        verify(requestMapper, never()).update(isNull(), any());
    }

    @Test
    void frozenFormerOwnerCanApproveAfterMailboxOwnerChanges() {
        authenticate(20L, false);
        SysUser frozenOwner = user(20L, "E20", "Former Owner");
        SysUser requester = user(10L, "E10", "Requester");
        TemplateHeader header = header("E10");
        SenderMailboxBindingRequest pending = pendingRequest();
        SenderMailboxBindingRequest approved = pendingRequest();
        approved.setStatus(SenderMailboxBindingApprovalService.APPROVED);
        when(sysUserMapper.selectById(20L)).thenReturn(frozenOwner);
        when(sysUserMapper.selectById(10L)).thenReturn(requester);
        when(requestMapper.selectById(900L)).thenReturn(pending, pending, approved);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header);
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenReturn(mailbox("E30"));
        when(requestMapper.update(isNull(), any())).thenReturn(1);

        SenderMailboxBindingApprovalService.RequestView result = service().decide(900L, "APPROVE", null);

        assertThat(result.request().getStatus()).isEqualTo(SenderMailboxBindingApprovalService.APPROVED);
        verify(requestMapper).lockTemplateHeader(50L);
        verify(authorizationMapper).insert(any(SenderMailboxBindingAuthorization.class));
        verify(templateHeaderMapper).updateById(any(TemplateHeader.class));
        verify(ownerResolver, never()).requireByEmployeeId(any());
    }

    @Test
    void approvalInvalidatesRequestWhenMailboxIsNoLongerAvailable() {
        authenticate(20L, false);
        SenderMailboxBindingRequest pending = pendingRequest();
        SenderMailboxBindingRequest invalidated = pendingRequest();
        invalidated.setStatus(SenderMailboxBindingApprovalService.INVALIDATED);
        invalidated.setInvalidationReason("申请邮箱已不可用");
        when(sysUserMapper.selectById(20L)).thenReturn(user(20L, "E20", "Mailbox Owner"));
        when(sysUserMapper.selectById(10L)).thenReturn(user(10L, "E10", "Requester"));
        when(requestMapper.selectById(900L)).thenReturn(pending, pending, invalidated);
        when(templateHeaderMapper.selectById(50L)).thenReturn(header("E10"));
        when(governanceService.isGlobalAdminUser(10L)).thenReturn(false);
        when(senderMailboxService.requireAvailableMailbox(70L)).thenThrow(new BizException("发件箱已停用"));
        when(requestMapper.update(isNull(), any())).thenReturn(1);

        SenderMailboxBindingApprovalService.RequestView result = service().decide(900L, "APPROVE", null);

        assertThat(result.request().getStatus()).isEqualTo(SenderMailboxBindingApprovalService.INVALIDATED);
        verify(templateHeaderMapper, never()).updateById(any(TemplateHeader.class));
        verify(eventPublisher, never()).publishEvent(any(SenderMailboxBindingNotificationRequested.class));
    }

    private SenderMailboxBindingApprovalService service() {
        return new SenderMailboxBindingApprovalService(
                requestMapper,
                authorizationMapper,
                templateHeaderMapper,
                mailboxMapper,
                sysUserMapper,
                senderMailboxService,
                ownerResolver,
                governanceService,
                auditLogService,
                eventPublisher);
    }

    private TemplateHeader header(String ownerRef) {
        TemplateHeader header = new TemplateHeader();
        header.setId(50L);
        header.setCode("TPL_TEST");
        header.setName("Test Template");
        header.setTemplateKind(TemplateCenterService.TEMPLATE_KIND_TASK);
        header.setOwnerUserId(ownerRef);
        return header;
    }

    private SenderMailbox mailbox(String ownerEmployeeId) {
        return mailbox(70L, ownerEmployeeId);
    }

    private SenderMailbox mailbox(Long id, String ownerEmployeeId) {
        SenderMailbox mailbox = new SenderMailbox();
        mailbox.setId(id);
        mailbox.setName("HR Mailbox");
        mailbox.setOwnerEmployeeId(ownerEmployeeId);
        return mailbox;
    }

    private SenderMailboxBindingRequest pendingRequest() {
        SenderMailboxBindingRequest request = new SenderMailboxBindingRequest();
        request.setId(900L);
        request.setTemplateHeaderId(50L);
        request.setRequestedSenderMailboxId(70L);
        request.setRequesterUserId(10L);
        request.setApproverUserId(20L);
        request.setStatus(SenderMailboxBindingApprovalService.PENDING);
        return request;
    }

    private SysUser user(Long id, String employeeId, String name) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setEmployeeId(employeeId);
        user.setUsername(employeeId.toLowerCase());
        user.setName(name);
        return user;
    }

    private void authenticate(Long userId, boolean globalAdmin) {
        List<org.springframework.security.core.GrantedAuthority> authorities = globalAdmin
                ? List.of(() -> "ROLE_GLOBAL_ADMIN")
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }
}
