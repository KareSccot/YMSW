package com.wuxibio.care.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.i18n.I18nMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new I18nMessageService(new ObjectMapper()));

    @Test
    void handleBizException_shouldUseHttpStatusFromCode() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Map<Integer, HttpStatus> expectedStatuses = Map.of(
                400, HttpStatus.BAD_REQUEST,
                401, HttpStatus.UNAUTHORIZED,
                403, HttpStatus.FORBIDDEN,
                404, HttpStatus.NOT_FOUND,
                500, HttpStatus.INTERNAL_SERVER_ERROR);

        expectedStatuses.forEach((code, status) -> {
            ResponseEntity<R<Void>> response = handler.handleBizException(new BizException(code, "error"), request);

            assertEquals(status, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(code, response.getBody().getCode());
            assertEquals("error", response.getBody().getMessage());
        });
    }

    @Test
    void handleBizException_shouldTranslateMessageByRequestLanguage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Language", "en-US");

        ResponseEntity<R<Void>> response = handler.handleBizException(new BizException(401, "未登录或登录已过期"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Not signed in or login expired", response.getBody().getMessage());
    }
}
