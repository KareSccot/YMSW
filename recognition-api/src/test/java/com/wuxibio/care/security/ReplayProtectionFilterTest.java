package com.wuxibio.care.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayProtectionFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-09T08:00:00Z"));
    private final ReplayNonceStore nonceStore = new ReplayNonceStore(clock, 100);
    private final ReplayProtectionFilter filter = new ReplayProtectionFilter(
            nonceStore, new ObjectMapper(), clock, true, 300_000);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getRequestsDoNotRequireReplayHeaders() throws ServletException, IOException {
        authenticate();
        MockHttpServletRequest request = request("GET", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void unsafeAuthenticatedRequestsRequireNonceAndTimestamp() throws ServletException, IOException {
        authenticate();
        MockHttpServletRequest request = request("POST", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
    }

    @Test
    void unsafeAuthenticatedRequestsAcceptFreshNonceOnce() throws ServletException, IOException {
        authenticate();
        MockHttpServletRequest first = signedRequest("POST", "/api/v1/users", "nonce-1234567890");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockFilterChain firstChain = new MockFilterChain();

        filter.doFilter(first, firstResponse, firstChain);

        assertNotNull(firstChain.getRequest());
        assertEquals(200, firstResponse.getStatus());

        MockHttpServletRequest replay = signedRequest("POST", "/api/v1/users", "nonce-1234567890");
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        MockFilterChain replayChain = new MockFilterChain();

        filter.doFilter(replay, replayResponse, replayChain);

        assertNull(replayChain.getRequest());
        assertEquals(409, replayResponse.getStatus());
    }

    @Test
    void unsafeAuthenticatedRequestsRejectExpiredTimestamp() throws ServletException, IOException {
        authenticate();
        MockHttpServletRequest request = signedRequest("POST", "/api/v1/users", "nonce-abcdef123456");
        request.removeHeader(ReplayProtectionFilter.HEADER_TIMESTAMP);
        request.addHeader(ReplayProtectionFilter.HEADER_TIMESTAMP, String.valueOf(clock.millis() - 301_000));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(400, response.getStatus());
    }

    @Test
    void unauthenticatedRequestsAreLeftForAuthenticationFilterChain() throws ServletException, IOException {
        MockHttpServletRequest request = request("POST", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void productionComponentsCanBeCreatedBySpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "app.security.replay.max-nonces=100",
                    "app.security.replay.enabled=true",
                    "app.security.replay.timestamp-window-ms=300000")
                    .applyTo(context);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ReplayNonceStore.class, ReplayProtectionFilter.class);

            context.refresh();

            assertNotNull(context.getBean(ReplayNonceStore.class));
            assertNotNull(context.getBean(ReplayProtectionFilter.class));
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of(() -> "ROLE_USER")));
    }

    private MockHttpServletRequest signedRequest(String method, String path, String nonce) {
        MockHttpServletRequest request = request(method, path);
        request.addHeader(ReplayProtectionFilter.HEADER_TIMESTAMP, String.valueOf(clock.millis()));
        request.addHeader(ReplayProtectionFilter.HEADER_NONCE, nonce);
        return request;
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static class MutableClock extends Clock {
        private final Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
