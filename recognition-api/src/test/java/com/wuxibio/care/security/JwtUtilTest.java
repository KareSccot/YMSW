package com.wuxibio.care.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static final String STRONG_SECRET = "01234567890123456789012345678901";
    private static final String LEGACY_DEFAULT_SECRET =
            "wuxibio-employee-care-jwt-secret-key-2026-must-be-at-least-256-bits";
    private static final String LOCAL_DEV_SECRET =
            "LOCAL-DEV-ONLY-DO-NOT-USE-IN-PRODUCTION-01234567890123456789";

    @Test
    void localProfileAllowsLocalDevFallbackSecret() {
        assertDoesNotThrow(() -> new JwtUtil(LOCAL_DEV_SECRET, 7200000, environment("local")));
    }

    @Test
    void defaultLocalProfileAllowsLocalDevFallbackSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");

        assertDoesNotThrow(() -> new JwtUtil(LOCAL_DEV_SECRET, 7200000, environment));
    }

    @Test
    void nonLocalProfileAllowsStrongNonDefaultSecret() {
        assertDoesNotThrow(() -> new JwtUtil(STRONG_SECRET, 7200000, environment("uat")));
    }

    @Test
    void nonLocalProfileRejectsLegacyDefaultSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(LEGACY_DEFAULT_SECRET, 7200000, environment("uat")));
    }

    @Test
    void nonLocalProfileRejectsLocalDevSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(LOCAL_DEV_SECRET, 7200000, environment("dev")));
    }

    @Test
    void mixedLocalAndNonLocalProfilesRejectLocalDevSecret() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil(LOCAL_DEV_SECRET, 7200000, environment("local", "uat")));
    }

    @Test
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtil("too-short", 7200000, environment("uat")));
    }

    private MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }
}
