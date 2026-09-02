package com.wuxibio.care.service;

import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.entity.SenderMailbox;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TemplateSenderMailboxService {

    private final TemplateHeaderMapper templateHeaderMapper;
    private final SenderMailboxService senderMailboxService;
    private final ExternalConnectionService connectionService;

    public TemplateSenderMailboxService(
            TemplateHeaderMapper templateHeaderMapper,
            SenderMailboxService senderMailboxService,
            ExternalConnectionService connectionService) {
        this.templateHeaderMapper = templateHeaderMapper;
        this.senderMailboxService = senderMailboxService;
        this.connectionService = connectionService;
    }

    public record Resolution(
            String source,
            Long senderMailboxId,
            Long externalConnectionId,
            String name,
            String host,
            String port,
            String username,
            String fromAddress,
            String fromName,
            String lastTestResult,
            Map<String, String> config,
            Map<String, String> metadata) {
    }

    public Resolution resolveForTemplateHeader(Long templateHeaderId) {
        if (templateHeaderId == null) {
            throw new BizException("模板组标识不能为空");
        }
        TemplateHeader header = templateHeaderMapper.selectById(templateHeaderId);
        if (header == null) {
            throw new BizException("模板组不存在");
        }
        if (header.getSenderMailboxId() != null) {
            return resolveSenderMailbox(header.getSenderMailboxId());
        }
        if (TemplateCenterService.TEMPLATE_KIND_TASK.equals(header.getTemplateKind())) {
            throw new BizException("任务模板组未配置发送发件箱，请先在模板中心完成绑定");
        }
        return resolveActiveSmtp();
    }

    public void requireBindableSenderMailbox(Long senderMailboxId) {
        if (senderMailboxId != null) {
            senderMailboxService.requireAvailableMailbox(senderMailboxId);
        }
    }

    public List<SendMailboxOption> listBindableOptions() {
        return senderMailboxService.listAvailableMailboxes().stream()
                .map(mailbox -> toOption(resolveSenderMailbox(mailbox)))
                .toList();
    }

    public SendMailboxOption toOption(Resolution resolution) {
        if (resolution == null) {
            return null;
        }
        String label = resolution.name() + " · " + resolution.fromName() + " <" + resolution.fromAddress() + ">";
        return new SendMailboxOption(
                resolution.source(),
                resolution.senderMailboxId(),
                resolution.externalConnectionId(),
                resolution.name(),
                label,
                resolution.host(),
                resolution.port(),
                resolution.username(),
                resolution.fromAddress(),
                resolution.fromName(),
                resolution.lastTestResult());
    }

    private Resolution resolveSenderMailbox(Long senderMailboxId) {
        return resolveSenderMailbox(senderMailboxService.requireAvailableMailbox(senderMailboxId));
    }

    private Resolution resolveSenderMailbox(SenderMailbox mailbox) {
        Map<String, String> config = senderMailboxService.buildSmtpConfig(mailbox, true);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(EmailChannel.METADATA_SENDER_MAILBOX_SOURCE, EmailChannel.MAILBOX_SOURCE_SENDER_MAILBOX);
        metadata.put(EmailChannel.METADATA_SENDER_MAILBOX_ID, String.valueOf(mailbox.getId()));
        return new Resolution(
                EmailChannel.MAILBOX_SOURCE_SENDER_MAILBOX,
                mailbox.getId(),
                null,
                mailbox.getName(),
                trim(config.get("host")),
                trim(config.get("port")),
                trim(config.get("username")),
                defaultIfBlank(config.get("fromAddress"), config.get("username")),
                defaultIfBlank(config.get("fromName"), "员工认可管理平台"),
                mailbox.getLastTestResult(),
                config,
                metadata);
    }

    private Resolution resolveActiveSmtp() {
        ExternalConnectionService.ConnectionConfig connection = connectionService.getActiveConnectionConfig("SMTP");
        if (connection == null) {
            return null;
        }
        Map<String, String> config = connection.config() == null ? Map.of() : connection.config();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(EmailChannel.METADATA_SENDER_MAILBOX_SOURCE, EmailChannel.MAILBOX_SOURCE_ACTIVE_SMTP);
        metadata.put(EmailChannel.METADATA_EXTERNAL_CONNECTION_ID, String.valueOf(connection.id()));
        return new Resolution(
                EmailChannel.MAILBOX_SOURCE_ACTIVE_SMTP,
                null,
                connection.id(),
                connection.name(),
                trim(config.get("host")),
                trim(config.get("port")),
                trim(config.get("username")),
                defaultIfBlank(config.get("fromAddress"), config.get("username")),
                defaultIfBlank(config.get("fromName"), "员工认可管理平台"),
                connection.lastTestResult(),
                config,
                metadata);
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isBlank() ? trim(fallback) : normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
