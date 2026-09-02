package com.wuxibio.care.common.enums;

/**
 * Lifecycle of an approval instance (txn_task_approval_instance.status) and
 * approval node instance (txn_task_approval_node_instance.status).
 *
 * Persisted as enum name() (case-sensitive).
 *  Pending   — waiting for approver decision
 *  Approved  — approved by the current node
 *  Rejected  — explicitly rejected
 *  Cancelled — withdrawn by requester or system
 *  Consumed  — terminal state after the approved task has been executed
 *  Waiting   — node-only: queued but not the active node yet
 */
public enum ApprovalStatus {
    Pending,
    Approved,
    Rejected,
    Cancelled,
    Consumed,
    Waiting;

    public boolean matches(String dbValue) {
        return this.name().equals(dbValue);
    }

    public static boolean isValid(String dbValue) {
        if (dbValue == null) return false;
        for (ApprovalStatus s : values()) {
            if (s.name().equals(dbValue)) return true;
        }
        return false;
    }
}
