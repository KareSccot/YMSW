package com.wuxibio.care.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtil {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String LEGACY_DEFAULT_SECRET_MARKER = "wuxibio-employee-care-jwt-secret-key-2026";
    private static final String LOCAL_DEV_SECRET_PREFIX = "LOCAL-DEV-ONLY";

    private final SecretKey key;
    private final long accessExpirationMs;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiration-ms}") long accessExpirationMs,
            Environment environment) {
        String validatedSecret = validateSecret(secret, environment);
        this.key = Keys.hmacShaKeyFor(validatedSecret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
    }

    private String validateSecret(String secret, Environment environment) {
        String normalized = secret == null ? "" : secret.trim();
        if (normalized.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JWT secret must be at least 32 characters");
        }

        boolean defaultOrLocalDevSecret = normalized.contains(LEGACY_DEFAULT_SECRET_MARKER)
                || normalized.startsWith(LOCAL_DEV_SECRET_PREFIX);
        if (defaultOrLocalDevSecret && !isLocalOnlyProfile(environment)) {
            throw new IllegalStateException("Non-local profiles cannot use default or local-dev JWT secrets");
        }
        return normalized;
    }

    private boolean isLocalOnlyProfile(Environment environment) {
        String[] activeProfiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return activeProfiles.length == 1 && "local".equals(activeProfiles[0]);
        }
        String[] defaultProfiles = environment == null ? new String[0] : environment.getDefaultProfiles();
        return Arrays.asList(defaultProfiles).contains("local");
    }

    public String generateToken(Long userId, String username, List<Long> roleIds, boolean globalAdmin) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("roleIds", roleIds)
                .claim("globalAdmin", globalAdmin)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        Object username = parseToken(token).get("username");
        return username == null ? null : username.toString();
    }

    @SuppressWarnings("unchecked")
    public List<Long> getRoleIds(String token) {
        Object roleIds = parseToken(token).get("roleIds");
        if (roleIds instanceof List<?> list) {
            return list.stream().map(o -> ((Number) o).longValue()).toList();
        }
        return List.of();
    }

    public boolean isGlobalAdmin(String token) {
        Object globalAdmin = parseToken(token).get("globalAdmin");
        if (globalAdmin instanceof Boolean b) return b;
        if (globalAdmin instanceof Number n) return n.intValue() != 0;
        if (globalAdmin instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return false;
    }
}
