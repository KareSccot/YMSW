package com.wuxibio.care.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ReplayProtectionFilter extends OncePerRequestFilter {

    public static final String HEADER_TIMESTAMP = "X-Request-Timestamp";
    public static final String HEADER_NONCE = "X-Request-Nonce";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private final ReplayNonceStore nonceStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean enabled;
    private final long timestampWindowMs;

    @Autowired
    public ReplayProtectionFilter(
            ReplayNonceStore nonceStore,
            ObjectMapper objectMapper,
            @Value("${app.security.replay.enabled:true}") boolean enabled,
            @Value("${app.security.replay.timestamp-window-ms:300000}") long timestampWindowMs) {
        this(nonceStore, objectMapper, Clock.systemUTC(), enabled, timestampWindowMs);
    }

    ReplayProtectionFilter(
            ReplayNonceStore nonceStore,
            ObjectMapper objectMapper,
            Clock clock,
            boolean enabled,
            long timestampWindowMs) {
        this.nonceStore = nonceStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.enabled = enabled;
        this.timestampWindowMs = Math.max(30_000, timestampWindowMs);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!shouldProtect(request)) {
            chain.doFilter(request, response);
            return;
        }

        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        if (!isValidNonce(nonce)) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "缺少或非法的请求 nonce");
            return;
        }

        Long timestamp = parseTimestamp(timestampHeader);
        if (timestamp == null || Math.abs(clock.millis() - timestamp) > timestampWindowMs) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "请求时间戳无效或已过期");
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principalKey = String.valueOf(authentication.getPrincipal());
        if (!nonceStore.register(principalKey, nonce, timestampWindowMs)) {
            writeError(response, HttpServletResponse.SC_CONFLICT, "检测到重复请求");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean shouldProtect(HttpServletRequest request) {
        if (!enabled || SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(request.getContextPath() + "/api/v1/")) {
            return false;
        }
        if (path.startsWith(request.getContextPath() + "/api/v1/auth/")) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }

    private boolean isValidNonce(String nonce) {
        return nonce != null && NONCE_PATTERN.matcher(nonce).matches();
    }

    private Long parseTimestamp(String timestampHeader) {
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(status, message)));
    }
}
