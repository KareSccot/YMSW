package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("txn_task_context_change_log")
public class TaskContextChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_run_id")
    private Long taskRunId;

    @TableField("recipient_id")
    private String recipientId;

    @TableField("changed_field")
    private String changedField;

    @TableField("before_value")
    private String beforeValue;

    @TableField("after_value")
    private String afterValue;

    @TableField("change_reason")
    private String changeReason;

    @TableField("changed_by")
    private String changedBy;

    @TableField("changed_at")
    private LocalDateTime changedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskRunId() { return taskRunId; }
    public void setTaskRunId(Long taskRunId) { this.taskRunId = taskRunId; }
    public String getRecipientId() { return recipientId; }
    public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
    public String getChangedField() { return changedField; }
    public void setChangedField(String changedField) { this.changedField = changedField; }
    public String getBeforeValue() { return beforeValue; }
    public void setBeforeValue(String beforeValue) { this.beforeValue = beforeValue; }
    public String getAfterValue() { return afterValue; }
    public void setAfterValue(String afterValue) { this.afterValue = afterValue; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
}
