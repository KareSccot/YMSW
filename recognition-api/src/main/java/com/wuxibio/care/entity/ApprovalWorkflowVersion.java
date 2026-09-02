package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** Immutable snapshot of one saved approval workflow version. */
@TableName("cfg_approval_workflow_version")
public class ApprovalWorkflowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("workflow_code")
    private String workflowCode;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("workflow_name")
    private String workflowName;

    private String description;

    @TableField("canvas_layout")
    private String canvasLayout;

    @TableField("nodes_snapshot_json")
    private String nodesSnapshotJson;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCanvasLayout() { return canvasLayout; }
    public void setCanvasLayout(String canvasLayout) { this.canvasLayout = canvasLayout; }
    public String getNodesSnapshotJson() { return nodesSnapshotJson; }
    public void setNodesSnapshotJson(String nodesSnapshotJson) { this.nodesSnapshotJson = nodesSnapshotJson; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
