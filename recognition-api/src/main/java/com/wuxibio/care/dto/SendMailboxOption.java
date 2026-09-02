package com.wuxibio.care.dto;

public record SendMailboxOption(
        String source,
        Long senderMailboxId,
        Long externalConnectionId,
        String name,
        String label,
        String host,
        String port,
        String username,
        String fromAddress,
        String fromName,
        String lastTestResult) {
}
