package com.wuxibio.care.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class StartupCredentialValidator {

    private final Environment environment;

    public StartupCredentialValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (isLocalOnlyProfile()) {
            return;
        }
        validateRequiredProperty("spring.datasource.username");
        validateRequiredProperty("spring.datasource.password");
        validateRequiredProperty("app.jwt.secret");
        validateRequiredProperty("app.security.data-key");
    }

    private void validateRequiredProperty(String propertyName) {
        String value;
        try {
            value = environment.getProperty(propertyName);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Non-local profiles require " + propertyName + " to be set", e);
        }
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.contains("${")) {
            throw new IllegalStateException("Non-local profiles require a resolved, non-empty " + propertyName);
        }
    }

    private boolean isLocalOnlyProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return activeProfiles.length == 1 && "local".equals(activeProfiles[0]);
        }
        return Arrays.asList(environment.getDefaultProfiles()).contains("local");
    }
}
