package com.wuxibio.care.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() {}

    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        if (auth.getDetails() instanceof String username && !username.isBlank()) {
            return username.trim();
        }
        if (auth.getPrincipal() instanceof String username && !username.isBlank()) {
            return username.trim();
        }
        return null;
    }

    public static boolean isAuthenticated() {
        return getCurrentUserId() != null;
    }

    public static boolean hasRole(Long roleId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + roleId));
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        boolean globalAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_GLOBAL_ADMIN".equals(a.getAuthority()));
        return globalAdmin || hasRole(1L);
    }
}
