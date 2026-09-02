package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 每个 task_run/workflow 一条审批实例。
 * 状态机: Pending → Approved | Rejected | Cancelled | Invalidated；Approved → Consumed。
 */
@TableName("txn_task_approval_instance")
public class TaskApprovalInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_run_id")
    private Long taskRunId;

    @TableField("tag_code")
    private String tagCode;

    @TableField("trigger_source")
    private String triggerSource;

    @TableField("trigger_refs_json")
    private String triggerRefsJson;

    @TableField("content_snapshot_json")
    private String contentSnapshotJson;

    @TableField("workflow_code")
    private String workflowCode;

    @TableField("workflow_version_no")
    private Integer workflowVersionNo;

    @TableField("workflow_snapshot_json")
    private String workflowSnapshotJson;

    private String status;

    @TableField("requested_by")
    private Long requestedBy;

    @TableField("requested_at")
    private LocalDateTime requestedAt;

    @TableField("decided_by")
    private Long decidedBy;

    @TableField("decided_at")
    private LocalDateTime decidedAt;

    @TableField("decision_comment")
    private String decisionComment;

    @TableField("cancel_source")
    private String cancelSource;

    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("consumed_flag")
    private Integer consumedFlag;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskRunId() { return taskRunId; }
    public void setTaskRunId(Long taskRunId) { this.taskRunId = taskRunId; }
    public String getTagCode() { return tagCode; }
    public void setTagCode(String tagCode) { this.tagCode = tagCode; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getTriggerRefsJson() { return triggerRefsJson; }
    public void setTriggerRefsJson(String triggerRefsJson) { this.triggerRefsJson = triggerRefsJson; }
    public String getContentSnapshotJson() { return contentSnapshotJson; }
    public void setContentSnapshotJson(String contentSnapshotJson) { this.contentSnapshotJson = contentSnapshotJson; }
    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
    public Integer getWorkflowVersionNo() { return workflowVersionNo; }
    public void setWorkflowVersionNo(Integer workflowVersionNo) { this.workflowVersionNo = workflowVersionNo; }
    public String getWorkflowSnapshotJson() { return workflowSnapshotJson; }
    public void setWorkflowSnapshotJson(String workflowSnapshotJson) { this.workflowSnapshotJson = workflowSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = decisionComment; }
    public String getCancelSource() { return cancelSource; }
    public void setCancelSource(String cancelSource) { this.cancelSource = cancelSource; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Integer getConsumedFlag() { return consumedFlag; }
    public void setConsumedFlag(Integer consumedFlag) { this.consumedFlag = consumedFlag; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
