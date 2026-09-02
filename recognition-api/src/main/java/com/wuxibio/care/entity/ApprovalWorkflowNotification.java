package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/**
 * 平台级审批生命周期事件 -> 通知模板/通道/收件人角色 的映射规则.
 * workflow_code 保留为兼容存储字段；新规则统一写入保留的全局 scope，
 * 历史 workflow-specific 行不参与运行时投递.
 */
@TableName("cfg_approval_workflow_notification")
public class ApprovalWorkflowNotification {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("workflow_code")
    private String workflowCode;

    @TableField("event_type")
    private String eventType;

    @TableField("recipient_role")
    private String recipientRole;

    @TableField("channel_code")
    private String channelCode;

    @TableField("template_id")
    private Long templateId;

    @TableField("template_variant_id")
    private Long templateVariantId;

    private Integer enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @JsonIgnore
    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getRecipientRole() { return recipientRole; }
    public void setRecipientRole(String recipientRole) { this.recipientRole = recipientRole; }
    public String getChannelCode() { return channelCode; }
    public void setChannelCode(String channelCode) { this.channelCode = channelCode; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getTemplateVariantId() { return templateVariantId; }
    public void setTemplateVariantId(Long templateVariantId) { this.templateVariantId = templateVariantId; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
