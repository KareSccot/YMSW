package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sender_mailbox_binding_request")
public class SenderMailboxBindingRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateHeaderId;
    private Long previousSenderMailboxId;
    private Long requestedSenderMailboxId;
    private Long requesterUserId;
    private String requesterEmployeeId;
    private String requesterNameSnapshot;
    private Long approverUserId;
    private String approverEmployeeId;
    private String approverNameSnapshot;
    private String templateCodeSnapshot;
    private String templateNameSnapshot;
    private String mailboxNameSnapshot;
    private String status;
    private String decisionSource;
    private String requestComment;
    private String decisionComment;
    private String invalidationReason;
    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateHeaderId() { return templateHeaderId; }
    public void setTemplateHeaderId(Long templateHeaderId) { this.templateHeaderId = templateHeaderId; }
    public Long getPreviousSenderMailboxId() { return previousSenderMailboxId; }
    public void setPreviousSenderMailboxId(Long previousSenderMailboxId) { this.previousSenderMailboxId = previousSenderMailboxId; }
    public Long getRequestedSenderMailboxId() { return requestedSenderMailboxId; }
    public void setRequestedSenderMailboxId(Long requestedSenderMailboxId) { this.requestedSenderMailboxId = requestedSenderMailboxId; }
    public Long getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(Long requesterUserId) { this.requesterUserId = requesterUserId; }
    public String getRequesterEmployeeId() { return requesterEmployeeId; }
    public void setRequesterEmployeeId(String requesterEmployeeId) { this.requesterEmployeeId = requesterEmployeeId; }
    public String getRequesterNameSnapshot() { return requesterNameSnapshot; }
    public void setRequesterNameSnapshot(String requesterNameSnapshot) { this.requesterNameSnapshot = requesterNameSnapshot; }
    public Long getApproverUserId() { return approverUserId; }
    public void setApproverUserId(Long approverUserId) { this.approverUserId = approverUserId; }
    public String getApproverEmployeeId() { return approverEmployeeId; }
    public void setApproverEmployeeId(String approverEmployeeId) { this.approverEmployeeId = approverEmployeeId; }
    public String getApproverNameSnapshot() { return approverNameSnapshot; }
    public void setApproverNameSnapshot(String approverNameSnapshot) { this.approverNameSnapshot = approverNameSnapshot; }
    public String getTemplateCodeSnapshot() { return templateCodeSnapshot; }
    public void setTemplateCodeSnapshot(String templateCodeSnapshot) { this.templateCodeSnapshot = templateCodeSnapshot; }
    public String getTemplateNameSnapshot() { return templateNameSnapshot; }
    public void setTemplateNameSnapshot(String templateNameSnapshot) { this.templateNameSnapshot = templateNameSnapshot; }
    public String getMailboxNameSnapshot() { return mailboxNameSnapshot; }
    public void setMailboxNameSnapshot(String mailboxNameSnapshot) { this.mailboxNameSnapshot = mailboxNameSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecisionSource() { return decisionSource; }
    public void setDecisionSource(String decisionSource) { this.decisionSource = decisionSource; }
    public String getRequestComment() { return requestComment; }
    public void setRequestComment(String requestComment) { this.requestComment = requestComment; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = decisionComment; }
    public String getInvalidationReason() { return invalidationReason; }
    public void setInvalidationReason(String invalidationReason) { this.invalidationReason = invalidationReason; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
