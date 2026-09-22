package com.wuxibio.care.dto;

import java.time.LocalDateTime;

public record SenderMailboxResponse(
        Long id,
        String name,
        String smtpServer,
        Integer smtpPort,
        String username,
        Integer useSsl,
        Integer isDefault,
        String status,
        String ownerEmployeeId,
        Long ownerUserId,
        String ownerName,
        String ownerUsername,
        String fromAddress,
        String fromName,
        String testRecipientWhitelist,
        String emailBlacklist,
        String emailWhitelist,
        LocalDateTime lastTestedAt,
        String lastTestResult,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
