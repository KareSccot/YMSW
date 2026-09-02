package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Runtime approval node instance for a workflow-level task approval instance.
 */
@TableName("txn_task_approval_node_instance")
public class TaskApprovalNodeInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("approval_instance_id")
    private Long approvalInstanceId;

    @TableField("workflow_code")
    private String workflowCode;

    @TableField("node_code")
    private String nodeCode;

    @TableField("node_name")
    private String nodeName;

    @TableField("approver_employee_id")
    private String approverEmployeeId;

    @TableField("approver_sys_user_id")
    private Long approverSysUserId;

    @TableField("sort_order")
    private Integer sortOrder;

    private String status;

    @TableField("decided_by")
    private Long decidedBy;

    @TableField("decided_at")
    private LocalDateTime decidedAt;

    @TableField("decision_comment")
    private String decisionComment;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getApprovalInstanceId() { return approvalInstanceId; }
    public void setApprovalInstanceId(Long approvalInstanceId) { this.approvalInstanceId = approvalInstanceId; }
    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getApproverEmployeeId() { return approverEmployeeId; }
    public void setApproverEmployeeId(String approverEmployeeId) { this.approverEmployeeId = approverEmployeeId; }
    public Long getApproverSysUserId() { return approverSysUserId; }
    public void setApproverSysUserId(Long approverSysUserId) { this.approverSysUserId = approverSysUserId; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Long decidedBy) { this.decidedBy = decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecisionComment() { return decisionComment; }
    public void setDecisionComment(String decisionComment) { this.decisionComment = decisionComment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
