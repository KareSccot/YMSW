package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 工作流定义. 审批人工号来自 sys_user.employee_id, 运行时直接解析到 sys_user.
 * canvasLayout 是前端画布坐标 JSON, 后端不解析.
 */
@TableName("cfg_approval_workflow_def")
public class ApprovalWorkflowDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("workflow_code")
    private String workflowCode;

    @TableField("workflow_name")
    private String workflowName;

    @TableField("approver_employee_id")
    private String approverEmployeeId;

    private String description;

    @TableField("canvas_layout")
    private String canvasLayout;

    @TableField("current_version_no")
    private Integer currentVersionNo;

    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public String getApproverEmployeeId() { return approverEmployeeId; }
    public void setApproverEmployeeId(String approverEmployeeId) { this.approverEmployeeId = approverEmployeeId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCanvasLayout() { return canvasLayout; }
    public void setCanvasLayout(String canvasLayout) { this.canvasLayout = canvasLayout; }
    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
