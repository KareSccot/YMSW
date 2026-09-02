package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("cfg_template_header")
public class TemplateHeader {

    @TableId(value = "template_id", type = IdType.AUTO)
    private Long id;

    @TableField("template_code")
    private String code;

    @TableField("template_name")
    private String name;

    @TableField("template_purpose")
    private String templatePurpose;

    @TableField("template_kind")
    private String templateKind;

    @TableField("sender_mailbox_id")
    private Long senderMailboxId;

    private String description;
    private String status;

    @TableField("owner_userid")
    private String ownerUserId;

    @TableLogic
    private Integer deleted;

    @TableField("effective_start_date")
    private LocalDate effectiveStartDate;

    @TableField("effective_end_date")
    private LocalDate effectiveEndDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTemplatePurpose() { return templatePurpose; }
    public void setTemplatePurpose(String templatePurpose) { this.templatePurpose = templatePurpose; }
    public String getTemplateKind() { return templateKind; }
    public void setTemplateKind(String templateKind) { this.templateKind = templateKind; }
    public Long getSenderMailboxId() { return senderMailboxId; }
    public void setSenderMailboxId(Long senderMailboxId) { this.senderMailboxId = senderMailboxId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDate getEffectiveStartDate() { return effectiveStartDate; }
    public void setEffectiveStartDate(LocalDate effectiveStartDate) { this.effectiveStartDate = effectiveStartDate; }
    public LocalDate getEffectiveEndDate() { return effectiveEndDate; }
    public void setEffectiveEndDate(LocalDate effectiveEndDate) { this.effectiveEndDate = effectiveEndDate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
