package com.wuxibio.care.common.enums;

/**
 * Lifecycle of a single task run (txn_task_run.status).
 *
 * Persisted as enum name() (case-sensitive).
 */
public enum TaskRunStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled;

    public boolean matches(String dbValue) {
        return this.name().equals(dbValue);
    }

    public static boolean isValid(String dbValue) {
        if (dbValue == null) return false;
        for (TaskRunStatus s : values()) {
            if (s.name().equals(dbValue)) return true;
        }
        return false;
    }
}
