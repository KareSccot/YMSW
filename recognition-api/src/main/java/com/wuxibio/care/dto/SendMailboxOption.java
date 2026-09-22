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
        String lastTestResult,
        String ownerEmployeeId,
        Long ownerUserId,
        String ownerName) {
    public SendMailboxOption(
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
        this(source, senderMailboxId, externalConnectionId, name, label, host, port, username,
                fromAddress, fromName, lastTestResult, null, null, null);
    }
}
