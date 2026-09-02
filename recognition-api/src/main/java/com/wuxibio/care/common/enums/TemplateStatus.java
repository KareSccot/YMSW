package com.wuxibio.care.common.enums;

/**
 * Lifecycle of a template header / channel variant.
 *  Draft     — being authored, not yet usable for sends
 *  Published — sendable, the only state Dashboard counts as "templateCount"
 *  Archived  — retired but kept for historical reference
 *
 * Persisted as enum name() (case-sensitive).
 */
public enum TemplateStatus {
    Draft,
    Published,
    Archived;

    public boolean matches(String dbValue) {
        return this.name().equals(dbValue);
    }

    public static boolean isValid(String dbValue) {
        if (dbValue == null) return false;
        for (TemplateStatus s : values()) {
            if (s.name().equals(dbValue)) return true;
        }
        return false;
    }
}
