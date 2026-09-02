package com.wuxibio.care.common.enums;

/**
 * Generic two-state lifecycle used by config tables (roles, workflows, tags,
 * filters, etc.) where rows can be turned on/off without being deleted.
 *
 * Persisted as the enum name() (case-sensitive: "Active" / "Inactive").
 */
public enum CommonStatus {
    Active,
    Inactive;

    public boolean matches(String dbValue) {
        return this.name().equals(dbValue);
    }

    public static boolean isValid(String dbValue) {
        if (dbValue == null) return false;
        for (CommonStatus s : values()) {
            if (s.name().equals(dbValue)) return true;
        }
        return false;
    }
}
