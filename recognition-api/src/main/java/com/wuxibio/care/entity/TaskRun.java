package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("txn_task_run")
public class TaskRun {

    @TableId(value = "task_run_id", type = IdType.AUTO)
    private Long id;

    @TableField("run_no")
    private String runNo;

    @TableField("task_template_id")
    private Long taskTemplateId;

    @TableField("channel_variant_id")
    private Long channelVariantId;

    @TableField("run_mode")
    private String triggerMode;

    private String status;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("success_count")
    private Integer successCount;

    @TableField("failed_count")
    private Integer failedCount;

    @TableField("suspended_count")
    private Integer suspendedCount;

    @TableField("operator_userid")
    private String startedBy;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("scope_snapshot_json")
    private String scopeSnapshotJson;

    @TableField("channel_selection_json")
    private String channelSelectionJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }
    public Long getTaskTemplateId() { return taskTemplateId; }
    public void setTaskTemplateId(Long taskTemplateId) { this.taskTemplateId = taskTemplateId; }
    public Long getChannelVariantId() { return channelVariantId; }
    public void setChannelVariantId(Long channelVariantId) { this.channelVariantId = channelVariantId; }
    public String getTriggerMode() { return triggerMode; }
    public void setTriggerMode(String triggerMode) { this.triggerMode = triggerMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getSuspendedCount() { return suspendedCount; }
    public void setSuspendedCount(Integer suspendedCount) { this.suspendedCount = suspendedCount; }
    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getScopeSnapshotJson() { return scopeSnapshotJson; }
    public void setScopeSnapshotJson(String scopeSnapshotJson) { this.scopeSnapshotJson = scopeSnapshotJson; }
    public String getChannelSelectionJson() { return channelSelectionJson; }
    public void setChannelSelectionJson(String channelSelectionJson) { this.channelSelectionJson = channelSelectionJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
