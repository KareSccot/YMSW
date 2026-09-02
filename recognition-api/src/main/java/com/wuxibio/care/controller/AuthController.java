package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.dto.LoginRequest;
import com.wuxibio.care.dto.LoginResponse;
import com.wuxibio.care.security.SecurityUtil;
import com.wuxibio.care.service.AuthService;
import com.wuxibio.care.service.ExternalConnectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final ExternalConnectionService externalConnectionService;

    public AuthController(AuthService authService, ExternalConnectionService externalConnectionService) {
        this.authService = authService;
        this.externalConnectionService = externalConnectionService;
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @GetMapping("/me")
    public R<LoginResponse> me() {
        return R.ok(authService.currentSession(SecurityUtil.getCurrentUserId()));
    }

    @GetMapping("/ias/authorize")
    public void iasAuthorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String redirectUrl = externalConnectionService.buildActiveIasAuthorizeUrl();
            log.info("IAS authorize redirect prepared: remoteAddr={}, target={}",
                    resolveClientIp(request), abbreviate(redirectUrl, 240));
            response.sendRedirect(redirectUrl);
        } catch (Exception e) {
            String errorRedirect = externalConnectionService.buildIasErrorRedirect(e.getMessage());
            log.warn("IAS authorize failed: remoteAddr={}, message={}, redirect={}",
                    resolveClientIp(request), e.getMessage(), abbreviate(errorRedirect, 240), e);
            response.sendRedirect(errorRedirect);
        }
    }

    @GetMapping("/ias/oauth")
    public void iasOauth(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        log.info("IAS oauth callback received: remoteAddr={}, state={}, code={}, error={}, errorDescription={}",
                resolveClientIp(request),
                normalize(state),
                mask(code),
                normalize(error),
                abbreviate(normalize(errorDescription), 240));
        try {
            if (!normalize(error).isBlank()) {
                String message = normalize(errorDescription).isBlank() ? normalize(error) : normalize(errorDescription);
                response.sendRedirect(externalConnectionService.buildIasErrorRedirect(message));
                return;
            }

            ExternalConnectionService.IasUserProfile profile =
                    externalConnectionService.resolveActiveIasUserProfileByCode(code);
            LoginResponse loginResponse = authService.loginByIasEmployeeId(profile.employeeId());
            String successRedirect = externalConnectionService.buildIasSuccessRedirect(loginResponse.getToken());
            log.info("IAS oauth login success: employeeId={}, email={}, redirect={}",
                    mask(profile.employeeId()), maskEmail(profile.email()), abbreviate(successRedirect, 240));
            response.sendRedirect(successRedirect);
        } catch (Exception e) {
            String errorRedirect = externalConnectionService.buildIasErrorRedirect(e.getMessage());
            log.warn("IAS oauth callback failed: remoteAddr={}, state={}, code={}, message={}, redirect={}",
                    resolveClientIp(request), normalize(state), mask(code), e.getMessage(), abbreviate(errorRedirect, 240), e);
            response.sendRedirect(errorRedirect);
        }
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        // Stateless JWT - client just deletes the token
        return R.ok();
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = normalize(request.getHeader("X-Forwarded-For"));
        if (!forwarded.isBlank()) {
            return forwarded;
        }
        return normalize(request.getRemoteAddr());
    }

    private String mask(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() <= 8) {
            return normalized.charAt(0) + "***" + normalized.charAt(normalized.length() - 1);
        }
        return normalized.substring(0, 4) + "***" + normalized.substring(normalized.length() - 4);
    }

    private String maskEmail(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || !normalized.contains("@")) {
            return "";
        }
        int at = normalized.indexOf('@');
        String local = normalized.substring(0, at);
        String domain = normalized.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    private String abbreviate(String value, int limit) {
        String normalized = normalize(value);
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit)) + "...";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
