package com.wuxibio.care.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupCredentialValidatorTest {

    @Test
    void localProfileAllowsMissingDataSourcePassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        assertDoesNotThrow(() -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileRequiresResolvedDataSourcePassword() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("spring.datasource.username", "admin");
        environment.setProperty("spring.datasource.password", "${UAT_MYSQL_PASSWORD}");

        assertThrows(IllegalStateException.class, () -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileRequiresNonBlankDataSourcePassword() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("spring.datasource.username", "admin");
        environment.setProperty("spring.datasource.password", "   ");

        assertThrows(IllegalStateException.class, () -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileRequiresResolvedDataSourceUsername() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("spring.datasource.username", "${UAT_MYSQL_USERNAME}");
        environment.setProperty("spring.datasource.password", "rotated-db-password");

        assertThrows(IllegalStateException.class, () -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileAcceptsAnyExplicitDataSourcePassword() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("spring.datasource.username", "admin");
        environment.setProperty("spring.datasource.password", "existing-server-password");

        assertDoesNotThrow(() -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileRequiresResolvedJwtSecret() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("app.jwt.secret", "${UAT_JWT_SECRET}");

        assertThrows(IllegalStateException.class, () -> new StartupCredentialValidator(environment).validate());
    }

    @Test
    void nonLocalProfileRequiresNonBlankDataKey() {
        MockEnvironment environment = nonLocalEnvironment();
        environment.setProperty("app.security.data-key", "   ");

        assertThrows(IllegalStateException.class, () -> new StartupCredentialValidator(environment).validate());
    }

    private MockEnvironment nonLocalEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("uat");
        environment.setProperty("spring.datasource.username", "uat-user");
        environment.setProperty("spring.datasource.password", "uat-password");
        environment.setProperty("app.jwt.secret", "unit-test-jwt-secret");
        environment.setProperty("app.security.data-key", "unit-test-data-key");
        return environment;
    }
}
