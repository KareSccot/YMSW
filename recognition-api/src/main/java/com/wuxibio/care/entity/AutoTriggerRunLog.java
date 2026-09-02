package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("auto_trigger_run_log")
public class AutoTriggerRunLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("trigger_id")
    private Long triggerId;

    @TableField("execution_mode")
    private String executionMode;

    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    @TableField("scheduled_fire_time")
    private LocalDateTime scheduledFireTime;

    @TableField("task_run_id")
    private Long taskRunId;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("active_lock")
    private String activeLock;

    private String status;

    private String message;

    @TableField("matched_count")
    private Integer matchedCount;

    @TableField("sent_count")
    private Integer sentCount;

    @TableField("failed_count")
    private Integer failedCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTriggerId() { return triggerId; }
    public void setTriggerId(Long triggerId) { this.triggerId = triggerId; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public LocalDateTime getTriggerTime() { return triggerTime; }
    public void setTriggerTime(LocalDateTime triggerTime) { this.triggerTime = triggerTime; }
    public LocalDateTime getScheduledFireTime() { return scheduledFireTime; }
    public void setScheduledFireTime(LocalDateTime scheduledFireTime) { this.scheduledFireTime = scheduledFireTime; }
    public Long getTaskRunId() { return taskRunId; }
    public void setTaskRunId(Long taskRunId) { this.taskRunId = taskRunId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getActiveLock() { return activeLock; }
    public void setActiveLock(String activeLock) { this.activeLock = activeLock; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getMatchedCount() { return matchedCount; }
    public void setMatchedCount(Integer matchedCount) { this.matchedCount = matchedCount; }
    public Integer getSentCount() { return sentCount; }
    public void setSentCount(Integer sentCount) { this.sentCount = sentCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
