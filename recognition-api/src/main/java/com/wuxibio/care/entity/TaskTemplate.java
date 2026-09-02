package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("cfg_task_template_header")
public class TaskTemplate {

    @TableId(value = "task_template_id", type = IdType.AUTO)
    private Long id;

    @TableField("task_template_code")
    private String code;

    @TableField("task_template_name")
    private String name;

    private String mode;

    @TableField("bound_template_id")
    private Long templateHeaderId;

    private String description;
    private String status;

    @TableField("owner_userid")
    private String ownerUserId;

    @TableField("target_group_id")
    private Long targetGroupId;

    @TableField("condition_rule_version_id")
    private Long conditionRuleVersionId;

    @TableField("auto_channel_variant_id")
    private Long autoChannelVariantId;

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
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Long getTemplateHeaderId() { return templateHeaderId; }
    public void setTemplateHeaderId(Long templateHeaderId) { this.templateHeaderId = templateHeaderId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getTargetGroupId() { return targetGroupId; }
    public void setTargetGroupId(Long targetGroupId) { this.targetGroupId = targetGroupId; }
    public Long getConditionRuleVersionId() { return conditionRuleVersionId; }
    public void setConditionRuleVersionId(Long conditionRuleVersionId) { this.conditionRuleVersionId = conditionRuleVersionId; }
    public Long getAutoChannelVariantId() { return autoChannelVariantId; }
    public void setAutoChannelVariantId(Long autoChannelVariantId) { this.autoChannelVariantId = autoChannelVariantId; }
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
