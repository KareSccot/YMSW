package com.wuxibio.care.service;

import com.wuxibio.care.entity.AdminOperationAuditLog;
import com.wuxibio.care.mapper.AdminOperationAuditLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditLogServiceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void databaseTimestampLogLeavesCreatedAtForTheDatabaseDefault() {
        AdminOperationAuditLogMapper mapper = mock(AdminOperationAuditLogMapper.class);
        AuditLogService service = new AuditLogService(mapper);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        7L,
                        null,
                        List.of(() -> "ROLE_2"));
        authentication.setDetails("operator7");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        service.logWithDatabaseTimestamp(
                "TEMPLATE_VARIANT_PREVIEW_SUCCESS",
                "TEMPLATE_CHANNEL_VARIANT",
                "20",
                "headerId=10");

        ArgumentCaptor<AdminOperationAuditLog> captor =
                ArgumentCaptor.forClass(AdminOperationAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOperatorUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getCreatedAt()).isNull();
    }
}
