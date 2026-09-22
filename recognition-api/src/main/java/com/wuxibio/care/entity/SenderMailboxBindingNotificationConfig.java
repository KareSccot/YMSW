package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Mailbox-binding lifecycle event to editable template-variant mapping. */
@TableName("cfg_sender_mailbox_binding_notification")
public class SenderMailboxBindingNotificationConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("event_type")
    private String eventType;

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
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
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
