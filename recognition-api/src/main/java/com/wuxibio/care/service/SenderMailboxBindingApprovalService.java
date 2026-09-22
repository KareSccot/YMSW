package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SenderMailboxBindingApprovalService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INVALIDATED = "INVALIDATED";
    public static final String OUTCOME_PENDING = "PENDING_CREATED";
    public static final String OUTCOME_BOUND_AUTHORIZED = "BOUND_AUTHORIZED";
    public static final String OUTCOME_BOUND_OWNER = "BOUND_OWNER";
    public static final String OUTCOME_NO_CHANGE = "NO_CHANGE";

    private static final String AUTHORIZATION_OWNER_APPROVAL = "OWNER_APPROVAL";
    private static final String AUTHORIZATION_OWNER_DIRECT = "OWNER_DIRECT";
    private static final String LEGACY_OWNER_SELF_DECISION = "OWNER_SELF";

    private final SenderMailboxBindingRequestMapper requestMapper;
    private final SenderMailboxBindingAuthorizationMapper authorizationMapper;
    private final TemplateHeaderMapper templateHeaderMapper;
    private final SenderMailboxMapper mailboxMapper;
    private final SysUserMapper sysUserMapper;
    private final SenderMailboxService senderMailboxService;
    private final MailboxOwnerResolver ownerResolver;
    private final GovernanceService governanceService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    public SenderMailboxBindingApprovalService(
            SenderMailboxBindingRequestMapper requestMapper,
            SenderMailboxBindingAuthorizationMapper authorizationMapper,
            TemplateHeaderMapper templateHeaderMapper,
            SenderMailboxMapper mailboxMapper,
            SysUserMapper sysUserMapper,
            SenderMailboxService senderMailboxService,
            MailboxOwnerResolver ownerResolver,
            GovernanceService governanceService,
            AuditLogService auditLogService,
            ApplicationEventPublisher eventPublisher) {
        this.requestMapper = requestMapper;
        this.authorizationMapper = authorizationMapper;
        this.templateHeaderMapper = templateHeaderMapper;
        this.mailboxMapper = mailboxMapper;
        this.sysUserMapper = sysUserMapper;
        this.senderMailboxService = senderMailboxService;
        this.ownerResolver = ownerResolver;
        this.governanceService = governanceService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public BindingActionResult request(Long templateHeaderId, Long senderMailboxId, String comment) {
        SysUser requester = requireCurrentUser();
        requestMapper.lockTemplateHeader(templateHeaderId);
        TemplateHeader header = requireTaskHeader(templateHeaderId);
        requireRequesterAuthority(header, requester.getId());
        SenderMailbox mailbox = senderMailboxService.requireAvailableMailbox(senderMailboxId);

        if (Objects.equals(header.getSenderMailboxId(), mailbox.getId())) {
            return new BindingActionResult(OUTCOME_NO_CHANGE, mailbox.getId(), null);
        }
        SenderMailboxBindingRequest pending = findPending(header.getId());
        if (pending != null) {
            throw new BizException("该模板组已有待审批的发件箱申请（"
                    + pending.getMailboxNameSnapshot() + "），请先等待处理或取消原申请");
        }
        SysUser owner = ownerResolver.requireByEmployeeId(mailbox.getOwnerEmployeeId());
        if (requester.getId().equals(owner.getId())) {
            authorize(header.getId(), mailbox.getId(), requester.getId(), AUTHORIZATION_OWNER_DIRECT, null);
            bind(header, mailbox.getId());
            auditLogService.log(
                    "SENDER_MAILBOX_BINDING_OWNER_DIRECT",
                    "SENDER_MAILBOX_BINDING_AUTHORIZATION",
                    header.getId() + ":" + mailbox.getId(),
                    "templateHeaderId=" + header.getId() + ", senderMailboxId=" + mailbox.getId()
                            + ", ownerUserId=" + requester.getId());
            return new BindingActionResult(OUTCOME_BOUND_OWNER, mailbox.getId(), null);
        }

        LocalDateTime now = LocalDateTime.now();
        SenderMailboxBindingRequest row = new SenderMailboxBindingRequest();
        row.setTemplateHeaderId(header.getId());
        row.setPreviousSenderMailboxId(header.getSenderMailboxId());
        row.setRequestedSenderMailboxId(mailbox.getId());
        row.setRequesterUserId(requester.getId());
        row.setRequesterEmployeeId(requester.getEmployeeId());
        row.setRequesterNameSnapshot(displayName(requester));
        row.setApproverUserId(owner.getId());
        row.setApproverEmployeeId(owner.getEmployeeId());
        row.setApproverNameSnapshot(displayName(owner));
        row.setTemplateCodeSnapshot(header.getCode());
        row.setTemplateNameSnapshot(header.getName());
        row.setMailboxNameSnapshot(mailbox.getName());
        row.setStatus(PENDING);
        row.setDecisionSource(null);
        row.setRequestComment(trimToNull(comment));
        row.setRequestedAt(now);
        row.setDecidedAt(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        requestMapper.insert(row);

        auditLogService.log(
                "SENDER_MAILBOX_BINDING_REQUESTED",
                "SENDER_MAILBOX_BINDING_REQUEST",
                String.valueOf(row.getId()),
                "templateHeaderId=" + header.getId() + ", senderMailboxId=" + mailbox.getId()
                        + ", approverUserId=" + owner.getId());
        publishNotification(row.getId(), SenderMailboxBindingNotificationService.EVENT_REQUESTED);
        return new BindingActionResult(OUTCOME_PENDING, header.getSenderMailboxId(), view(row, requester.getId()));
    }

    @Transactional
    public RequestView decide(Long requestId, String decision, String comment) {
        Long actorId = requireCurrentUser().getId();
        SenderMailboxBindingRequest row = requireRequest(requestId);
        if (!PENDING.equals(row.getStatus())) throw new BizException("该邮箱绑定申请已处理");
        if (!actorId.equals(row.getApproverUserId())) {
            throw new BizException(403, "仅申请时冻结的邮箱 Owner 可以审批；Global Admin 不能代批");
        }
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase();
        if (!"APPROVE".equals(normalizedDecision) && !"REJECT".equals(normalizedDecision)) {
            throw new BizException("审批动作仅支持 APPROVE/REJECT");
        }

        String targetStatus = "APPROVE".equals(normalizedDecision) ? APPROVED : REJECTED;
        TemplateHeader header = null;
        if (APPROVED.equals(targetStatus)) {
            requestMapper.lockTemplateHeader(row.getTemplateHeaderId());
            row = requireRequest(requestId);
            if (!PENDING.equals(row.getStatus())) throw new BizException("该邮箱绑定申请已处理");
            if (!actorId.equals(row.getApproverUserId())) {
                throw new BizException(403, "仅申请时冻结的邮箱 Owner 可以审批；Global Admin 不能代批");
            }
            try {
                header = requireTaskHeader(row.getTemplateHeaderId());
            } catch (BizException e) {
                return invalidateAndView(row, actorId, "模板组已不存在或不再是 TASK 模板组: " + e.getMessage());
            }
            if (!hasRequesterAuthority(header, row.getRequesterUserId())) {
                return invalidateAndView(row, actorId, "申请人已不再是模板组 Owner 或 Global Admin");
            }
            try {
                senderMailboxService.requireAvailableMailbox(row.getRequestedSenderMailboxId());
            } catch (BizException e) {
                return invalidateAndView(row, actorId, "申请邮箱已不可用: " + e.getMessage());
            }
        }

        int updated = requestMapper.update(null, new LambdaUpdateWrapper<SenderMailboxBindingRequest>()
                .eq(SenderMailboxBindingRequest::getId, row.getId())
                .eq(SenderMailboxBindingRequest::getStatus, PENDING)
                .set(SenderMailboxBindingRequest::getStatus, targetStatus)
                .set(SenderMailboxBindingRequest::getDecisionSource, "OWNER_ACTION")
                .set(SenderMailboxBindingRequest::getDecisionComment, trimToNull(comment))
                .set(SenderMailboxBindingRequest::getDecidedAt, LocalDateTime.now())
                .set(SenderMailboxBindingRequest::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) throw new BizException("申请状态已变化，请刷新后重试");
        if (APPROVED.equals(targetStatus)) {
            authorize(
                    header.getId(),
                    row.getRequestedSenderMailboxId(),
                    actorId,
                    AUTHORIZATION_OWNER_APPROVAL,
                    row.getId());
            bind(header, row.getRequestedSenderMailboxId());
        }
        auditLogService.log(
                APPROVED.equals(targetStatus) ? "SENDER_MAILBOX_BINDING_APPROVED" : "SENDER_MAILBOX_BINDING_REJECTED",
                "SENDER_MAILBOX_BINDING_REQUEST",
                String.valueOf(row.getId()),
                "decision=" + targetStatus + ", actorUserId=" + actorId);
        publishNotification(row.getId(), APPROVED.equals(targetStatus)
                ? SenderMailboxBindingNotificationService.EVENT_APPROVED
                : SenderMailboxBindingNotificationService.EVENT_REJECTED);
        return view(requireRequest(row.getId()), actorId);
    }

    @Transactional
    public RequestView cancel(Long requestId, String reason) {
        Long actorId = requireCurrentUser().getId();
        SenderMailboxBindingRequest row = requireRequest(requestId);
        if (!actorId.equals(row.getRequesterUserId())) throw new BizException(403, "仅申请人可以取消申请");
        if (!PENDING.equals(row.getStatus())) throw new BizException("只有待审批申请可以取消");
        int updated = requestMapper.update(null, new LambdaUpdateWrapper<SenderMailboxBindingRequest>()
                .eq(SenderMailboxBindingRequest::getId, row.getId())
                .eq(SenderMailboxBindingRequest::getStatus, PENDING)
                .set(SenderMailboxBindingRequest::getStatus, CANCELLED)
                .set(SenderMailboxBindingRequest::getInvalidationReason, trimToNull(reason))
                .set(SenderMailboxBindingRequest::getDecidedAt, LocalDateTime.now())
                .set(SenderMailboxBindingRequest::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) throw new BizException("申请状态已变化，请刷新后重试");
        auditLogService.log("SENDER_MAILBOX_BINDING_CANCELLED", "SENDER_MAILBOX_BINDING_REQUEST",
                String.valueOf(row.getId()), "actorUserId=" + actorId);
        publishNotification(row.getId(), SenderMailboxBindingNotificationService.EVENT_CANCELLED);
        return view(requireRequest(row.getId()), actorId);
    }

    public Map<String, Object> page(int page, int size, String scope, String status) {
        Long actorId = requireCurrentUser().getId();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String normalizedScope = scope == null ? "assigned" : scope.trim().toLowerCase();
        LambdaQueryWrapper<SenderMailboxBindingRequest> wrapper = new LambdaQueryWrapper<SenderMailboxBindingRequest>()
                .and(decision -> decision
                        .isNull(SenderMailboxBindingRequest::getDecisionSource)
                        .or()
                        .ne(SenderMailboxBindingRequest::getDecisionSource, LEGACY_OWNER_SELF_DECISION));
        if ("assigned".equals(normalizedScope)) {
            wrapper.eq(SenderMailboxBindingRequest::getApproverUserId, actorId)
                    .eq(SenderMailboxBindingRequest::getStatus, PENDING);
        } else if ("created".equals(normalizedScope)) {
            wrapper.eq(SenderMailboxBindingRequest::getRequesterUserId, actorId);
        } else {
            throw new BizException("scope 仅支持 assigned/created");
        }
        if (!"assigned".equals(normalizedScope) && status != null && !status.isBlank()) {
            wrapper.eq(SenderMailboxBindingRequest::getStatus, status.trim().toUpperCase());
        }
        List<SenderMailboxBindingRequest> all = requestMapper.selectList(wrapper
                .orderByDesc(SenderMailboxBindingRequest::getRequestedAt)
                .orderByDesc(SenderMailboxBindingRequest::getId));
        int from = (safePage - 1) * safeSize;
        int to = Math.min(all.size(), from + safeSize);
        List<RequestView> records = from >= all.size() ? List.of() : all.subList(from, to).stream()
                .map(row -> view(row, actorId))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    public RequestView get(Long requestId) {
        Long actorId = requireCurrentUser().getId();
        SenderMailboxBindingRequest row = requireRequest(requestId);
        if (!canRead(row, actorId)) throw new BizException(403, "无权查看该邮箱绑定申请");
        return view(row, actorId);
    }

    public BindingState bindingState(Long templateHeaderId) {
        SysUser actor = requireCurrentUser();
        TemplateHeader header = requireTaskHeader(templateHeaderId);
        requireRequesterAuthority(header, actor.getId());
        SenderMailboxBindingRequest pending = findPending(header.getId());
        List<Long> authorizedMailboxIds = authorizationMapper.selectList(
                        new LambdaQueryWrapper<SenderMailboxBindingAuthorization>()
                                .eq(SenderMailboxBindingAuthorization::getTemplateHeaderId, header.getId())
                                .orderByAsc(SenderMailboxBindingAuthorization::getSenderMailboxId))
                .stream()
                .map(SenderMailboxBindingAuthorization::getSenderMailboxId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        String employeeId = nullToEmpty(actor.getEmployeeId());
        List<Long> ownerMailboxIds = employeeId.isBlank()
                ? List.of()
                : senderMailboxService.listAvailableMailboxes().stream()
                        .filter(mailbox -> employeeId.equalsIgnoreCase(nullToEmpty(mailbox.getOwnerEmployeeId())))
                        .map(SenderMailbox::getId)
                        .filter(Objects::nonNull)
                        .toList();
        return new BindingState(
                header.getSenderMailboxId(),
                pending == null ? null : view(pending, actor.getId()),
                authorizedMailboxIds,
                ownerMailboxIds);
    }

    public long countPendingAssigned(Long userId) {
        if (userId == null) return 0;
        Long count = requestMapper.selectCount(new LambdaQueryWrapper<SenderMailboxBindingRequest>()
                .eq(SenderMailboxBindingRequest::getApproverUserId, userId)
                .eq(SenderMailboxBindingRequest::getStatus, PENDING));
        return count == null ? 0 : count;
    }

    public boolean hasMailboxModuleAccess(Long userId) {
        if (userId == null) return false;
        SysUser user = sysUserMapper.selectById(userId);
        if (user != null && user.getEmployeeId() != null && !user.getEmployeeId().isBlank()) {
            Long owned = mailboxMapper.selectCount(new LambdaQueryWrapper<SenderMailbox>()
                    .eq(SenderMailbox::getOwnerEmployeeId, user.getEmployeeId().trim()));
            if (owned != null && owned > 0) return true;
        }
        Long related = requestMapper.selectCount(new LambdaQueryWrapper<SenderMailboxBindingRequest>()
                .and(decision -> decision
                        .isNull(SenderMailboxBindingRequest::getDecisionSource)
                        .or()
                        .ne(SenderMailboxBindingRequest::getDecisionSource, LEGACY_OWNER_SELF_DECISION))
                .and(wrapper -> wrapper.eq(SenderMailboxBindingRequest::getRequesterUserId, userId)
                        .or()
                        .eq(SenderMailboxBindingRequest::getApproverUserId, userId)));
        return related != null && related > 0;
    }

    private void bind(TemplateHeader header, Long mailboxId) {
        TemplateHeader update = new TemplateHeader();
        update.setId(header.getId());
        update.setSenderMailboxId(mailboxId);
        templateHeaderMapper.updateById(update);
    }

    private SenderMailboxBindingRequest findPending(Long templateHeaderId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<SenderMailboxBindingRequest>()
                .eq(SenderMailboxBindingRequest::getTemplateHeaderId, templateHeaderId)
                .eq(SenderMailboxBindingRequest::getStatus, PENDING)
                .orderByDesc(SenderMailboxBindingRequest::getRequestedAt)
                .orderByDesc(SenderMailboxBindingRequest::getId)
                .last("LIMIT 1"));
    }

    private boolean isAuthorized(Long templateHeaderId, Long senderMailboxId) {
        Long count = authorizationMapper.selectCount(
                new LambdaQueryWrapper<SenderMailboxBindingAuthorization>()
                        .eq(SenderMailboxBindingAuthorization::getTemplateHeaderId, templateHeaderId)
                        .eq(SenderMailboxBindingAuthorization::getSenderMailboxId, senderMailboxId));
        return count != null && count > 0;
    }

    private void authorize(
            Long templateHeaderId,
            Long senderMailboxId,
            Long authorizedByUserId,
            String source,
            Long requestId) {
        if (isAuthorized(templateHeaderId, senderMailboxId)) return;
        LocalDateTime now = LocalDateTime.now();
        SenderMailboxBindingAuthorization authorization = new SenderMailboxBindingAuthorization();
        authorization.setTemplateHeaderId(templateHeaderId);
        authorization.setSenderMailboxId(senderMailboxId);
        authorization.setAuthorizedByUserId(authorizedByUserId);
        authorization.setAuthorizationSource(source);
        authorization.setAuthorizationRequestId(requestId);
        authorization.setAuthorizedAt(now);
        authorization.setCreatedAt(now);
        authorization.setUpdatedAt(now);
        authorizationMapper.insert(authorization);
    }

    private RequestView invalidateAndView(SenderMailboxBindingRequest row, Long actorId, String reason) {
        int updated = requestMapper.update(null, new LambdaUpdateWrapper<SenderMailboxBindingRequest>()
                .eq(SenderMailboxBindingRequest::getId, row.getId())
                .eq(SenderMailboxBindingRequest::getStatus, PENDING)
                .set(SenderMailboxBindingRequest::getStatus, INVALIDATED)
                .set(SenderMailboxBindingRequest::getInvalidationReason, reason)
                .set(SenderMailboxBindingRequest::getDecidedAt, LocalDateTime.now())
                .set(SenderMailboxBindingRequest::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) throw new BizException("申请状态已变化，请刷新后重试");
        auditLogService.log("SENDER_MAILBOX_BINDING_INVALIDATED", "SENDER_MAILBOX_BINDING_REQUEST",
                String.valueOf(row.getId()), reason);
        return view(requireRequest(row.getId()), actorId);
    }

    private void publishNotification(Long requestId, String eventType) {
        eventPublisher.publishEvent(new SenderMailboxBindingNotificationRequested(requestId, eventType));
    }

    private TemplateHeader requireTaskHeader(Long id) {
        if (id == null) throw new BizException("模板组 ID 不能为空");
        TemplateHeader header = templateHeaderMapper.selectById(id);
        if (header == null) throw new BizException("模板组不存在");
        if (!TemplateCenterService.TEMPLATE_KIND_TASK.equals(header.getTemplateKind())) {
            throw new BizException("仅 TASK 模板组需要邮箱绑定审批");
        }
        if (TemplateCenterService.TEMPLATE_CODE_MAILBOX_BINDING_NOTIFICATION.equals(header.getCode())) {
            throw new BizException("邮箱绑定审批系统模板固定使用 Active SMTP，不允许申请 Sender Mailbox");
        }
        return header;
    }

    private SenderMailboxBindingRequest requireRequest(Long id) {
        if (id == null) throw new BizException("申请 ID 不能为空");
        SenderMailboxBindingRequest row = requestMapper.selectById(id);
        if (row == null) throw new BizException("邮箱绑定申请不存在");
        return row;
    }

    private SysUser requireCurrentUser() {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) throw new BizException(401, "未登录");
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BizException(401, "当前用户不存在");
        return user;
    }

    private void requireRequesterAuthority(TemplateHeader header, Long userId) {
        if (!hasRequesterAuthority(header, userId)) {
            throw new BizException(403, "仅模板组 Owner 或 Global Admin 可以申请绑定发件箱");
        }
    }

    private boolean hasRequesterAuthority(TemplateHeader header, Long userId) {
        if (userId == null || header == null) return false;
        if (SecurityUtil.getCurrentUserId() != null && SecurityUtil.getCurrentUserId().equals(userId) && SecurityUtil.isAdmin()) return true;
        if (governanceService.isGlobalAdminUser(userId)) return true;
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || header.getOwnerUserId() == null) return false;
        String ownerRef = header.getOwnerUserId().trim();
        return ownerRef.equals(String.valueOf(userId))
                || ownerRef.equalsIgnoreCase(nullToEmpty(user.getUsername()))
                || ownerRef.equalsIgnoreCase(nullToEmpty(user.getEmployeeId()));
    }

    private boolean canRead(SenderMailboxBindingRequest row, Long actorId) {
        return actorId.equals(row.getRequesterUserId())
                || actorId.equals(row.getApproverUserId());
    }

    private RequestView view(SenderMailboxBindingRequest row, Long actorId) {
        return new RequestView(
                row,
                PENDING.equals(row.getStatus()) && actorId.equals(row.getApproverUserId()),
                PENDING.equals(row.getStatus()) && actorId.equals(row.getRequesterUserId()));
    }

    private String displayName(SysUser user) {
        return user.getName() == null || user.getName().isBlank() ? user.getUsername() : user.getName();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record RequestView(SenderMailboxBindingRequest request, boolean canApprove, boolean canCancel) {}

    public record BindingActionResult(
            String outcome,
            Long effectiveSenderMailboxId,
            RequestView request) {}

    public record BindingState(
            Long currentSenderMailboxId,
            RequestView pendingRequest,
            List<Long> authorizedMailboxIds,
            List<Long> ownerMailboxIds) {}
}
