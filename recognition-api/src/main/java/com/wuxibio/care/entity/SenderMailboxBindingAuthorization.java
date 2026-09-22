package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Durable authorization for one template group to use one sender mailbox. */
@TableName("sender_mailbox_binding_authorization")
public class SenderMailboxBindingAuthorization {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateHeaderId;
    private Long senderMailboxId;
    private Long authorizedByUserId;
    private String authorizationSource;
    private Long authorizationRequestId;
    private LocalDateTime authorizedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTemplateHeaderId() { return templateHeaderId; }
    public void setTemplateHeaderId(Long templateHeaderId) { this.templateHeaderId = templateHeaderId; }
    public Long getSenderMailboxId() { return senderMailboxId; }
    public void setSenderMailboxId(Long senderMailboxId) { this.senderMailboxId = senderMailboxId; }
    public Long getAuthorizedByUserId() { return authorizedByUserId; }
    public void setAuthorizedByUserId(Long authorizedByUserId) { this.authorizedByUserId = authorizedByUserId; }
    public String getAuthorizationSource() { return authorizationSource; }
    public void setAuthorizationSource(String authorizationSource) { this.authorizationSource = authorizationSource; }
    public Long getAuthorizationRequestId() { return authorizationRequestId; }
    public void setAuthorizationRequestId(Long authorizationRequestId) { this.authorizationRequestId = authorizationRequestId; }
    public LocalDateTime getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(LocalDateTime authorizedAt) { this.authorizedAt = authorizedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
