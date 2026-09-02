package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("cfg_auto_trigger_def")
public class AutoTriggerDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    @TableField("task_template_id")
    private Long taskTemplateId;

    private String channel;

    @TableField("cron_expr")
    private String cronExpr;

    /** IANA zone id used to interpret cron_expr (e.g. Asia/Shanghai, UTC). */
    private String timezone;

    @TableField("condition_expression")
    private String conditionExpression;

    @TableField("scope_condition_expression")
    private String scopeConditionExpression;

    @TableField("condition_rule_version_id")
    private Long conditionRuleVersionId;

    @TableField("badi_binding_id")
    private String badiBindingId;

    private String status;

    @TableField("effective_start_date")
    private LocalDate effectiveStartDate;

    @TableField("effective_end_date")
    private LocalDate effectiveEndDate;

    @TableField("last_run_at")
    private LocalDateTime lastRunAt;

    @TableField("next_run_at")
    private LocalDateTime nextRunAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getTaskTemplateId() { return taskTemplateId; }
    public void setTaskTemplateId(Long taskTemplateId) { this.taskTemplateId = taskTemplateId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getConditionExpression() { return conditionExpression; }
    public void setConditionExpression(String conditionExpression) { this.conditionExpression = conditionExpression; }
    public String getScopeConditionExpression() { return scopeConditionExpression; }
    public void setScopeConditionExpression(String scopeConditionExpression) { this.scopeConditionExpression = scopeConditionExpression; }
    public Long getConditionRuleVersionId() { return conditionRuleVersionId; }
    public void setConditionRuleVersionId(Long conditionRuleVersionId) { this.conditionRuleVersionId = conditionRuleVersionId; }
    public String getBadiBindingId() { return badiBindingId; }
    public void setBadiBindingId(String badiBindingId) { this.badiBindingId = badiBindingId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getEffectiveStartDate() { return effectiveStartDate; }
    public void setEffectiveStartDate(LocalDate effectiveStartDate) { this.effectiveStartDate = effectiveStartDate; }
    public LocalDate getEffectiveEndDate() { return effectiveEndDate; }
    public void setEffectiveEndDate(LocalDate effectiveEndDate) { this.effectiveEndDate = effectiveEndDate; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
