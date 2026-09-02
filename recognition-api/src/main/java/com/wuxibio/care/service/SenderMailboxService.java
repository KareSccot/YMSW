package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.SenderMailboxRequest;
import com.wuxibio.care.dto.SenderMailboxResponse;
import com.wuxibio.care.entity.SenderMailbox;
import com.wuxibio.care.entity.TemplateHeader;
import com.wuxibio.care.mapper.SenderMailboxMapper;
import com.wuxibio.care.mapper.TemplateHeaderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SenderMailboxService {

    public static final String STATUS_ACTIVE = "Active";
    public static final String STATUS_INACTIVE = "Inactive";

    private final SenderMailboxMapper mailboxMapper;
    private final SensitiveDataCryptoService cryptoService;
    private final ExternalConnectionService connectionService;
    private final TemplateHeaderMapper templateHeaderMapper;

    public SenderMailboxService(
            SenderMailboxMapper mailboxMapper,
            SensitiveDataCryptoService cryptoService,
            ExternalConnectionService connectionService,
            TemplateHeaderMapper templateHeaderMapper) {
        this.mailboxMapper = mailboxMapper;
        this.cryptoService = cryptoService;
        this.connectionService = connectionService;
        this.templateHeaderMapper = templateHeaderMapper;
    }

    public List<SenderMailboxResponse> listAll() {
        return mailboxMapper.selectList(new LambdaQueryWrapper<SenderMailbox>()
                        .orderByAsc(SenderMailbox::getStatus)
                        .orderByDesc(SenderMailbox::getUpdatedAt)
                        .orderByAsc(SenderMailbox::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public SenderMailboxResponse getById(Long id) {
        return toResponse(requireMailbox(id));
    }

    public List<SenderMailbox> listAvailableMailboxes() {
        return mailboxMapper.selectList(new LambdaQueryWrapper<SenderMailbox>()
                        .eq(SenderMailbox::getStatus, STATUS_ACTIVE)
                        .orderByAsc(SenderMailbox::getId))
                .stream()
                .filter(this::isAvailable)
                .toList();
    }

    @Transactional
    public SenderMailboxResponse create(SenderMailboxRequest request) {
        SenderMailbox mailbox = new SenderMailbox();
        mailbox.setName(trim(request.getName()));
        mailbox.setSmtpServer(trim(request.getSmtpServer()));
        mailbox.setSmtpPort(request.getSmtpPort());
        mailbox.setUsername(trim(request.getUsername()));
        mailbox.setUseSsl(request.getUseSsl() == null ? 1 : request.getUseSsl());
        mailbox.setStatus(normalizeStatus(request.getStatus()));
        mailbox.setFromAddress(defaultIfBlank(request.getFromAddress(), mailbox.getUsername()));
        mailbox.setFromName(defaultIfBlank(request.getFromName(), "员工认可管理平台"));
        mailbox.setTestRecipientWhitelist(trimToNull(request.getTestRecipientWhitelist()));
        mailbox.setEmailBlacklist(trimToNull(request.getEmailBlacklist()));
        mailbox.setEmailWhitelist(trimToNull(request.getEmailWhitelist()));
        mailbox.setIsDefault(0);
        mailbox.setPassword(trim(request.getPassword()));
        validate(mailbox, true);
        mailbox.setPassword(cryptoService.encryptIfNeeded(mailbox.getPassword()));
        mailboxMapper.insert(mailbox);
        return toResponse(mailbox);
    }

    @Transactional
    public SenderMailboxResponse update(Long id, SenderMailboxRequest request) {
        SenderMailbox existing = requireMailbox(id);

        SenderMailbox effective = copyOf(existing);
        if (request.getName() != null) effective.setName(trim(request.getName()));
        if (request.getSmtpServer() != null) effective.setSmtpServer(trim(request.getSmtpServer()));
        if (request.getSmtpPort() != null) effective.setSmtpPort(request.getSmtpPort());
        if (request.getUsername() != null) effective.setUsername(trim(request.getUsername()));
        if (request.getUseSsl() != null) effective.setUseSsl(request.getUseSsl());
        if (request.getStatus() != null) effective.setStatus(normalizeStatus(request.getStatus()));
        if (request.getFromAddress() != null) effective.setFromAddress(defaultIfBlank(request.getFromAddress(), effective.getUsername()));
        if (request.getFromName() != null) effective.setFromName(defaultIfBlank(request.getFromName(), "员工认可管理平台"));
        if (request.getTestRecipientWhitelist() != null) effective.setTestRecipientWhitelist(trimToNull(request.getTestRecipientWhitelist()));
        if (request.getEmailBlacklist() != null) effective.setEmailBlacklist(trimToNull(request.getEmailBlacklist()));
        if (request.getEmailWhitelist() != null) effective.setEmailWhitelist(trimToNull(request.getEmailWhitelist()));

        String incomingPassword = request.getPassword() == null ? null : request.getPassword().trim();
        boolean changingPassword = incomingPassword != null && !incomingPassword.isBlank() && !"******".equals(incomingPassword);
        validate(effective, changingPassword || existing.getPassword() == null || existing.getPassword().isBlank());

        SenderMailbox update = new SenderMailbox();
        update.setId(id);
        update.setName(effective.getName());
        update.setSmtpServer(effective.getSmtpServer());
        update.setSmtpPort(effective.getSmtpPort());
        update.setUsername(effective.getUsername());
        update.setUseSsl(effective.getUseSsl());
        update.setStatus(effective.getStatus());
        update.setFromAddress(effective.getFromAddress());
        update.setFromName(effective.getFromName());
        update.setTestRecipientWhitelist(effective.getTestRecipientWhitelist());
        update.setEmailBlacklist(effective.getEmailBlacklist());
        update.setEmailWhitelist(effective.getEmailWhitelist());
        if (changingPassword) {
            update.setPassword(cryptoService.encryptIfNeeded(incomingPassword));
        }
        mailboxMapper.updateById(update);
        return toResponse(effective);
    }

    @Transactional
    public void delete(Long id) {
        requireMailbox(id);
        Long referenceCount = templateHeaderMapper.selectCount(new QueryWrapper<TemplateHeader>()
                .eq("sender_mailbox_id", id));
        if (referenceCount != null && referenceCount > 0) {
            throw new BizException("该发件箱仍被模板组使用，请先解除模板组绑定");
        }
        mailboxMapper.deleteById(id);
    }

    @Transactional
    public Map<String, Object> test(Long id) {
        SenderMailbox mailbox = requireMailbox(id);
        Map<String, Object> result = new LinkedHashMap<>();
        String testResult;
        try {
            String message = connectionService.testSmtpConfig(buildSmtpConfig(mailbox, false));
            testResult = "Success";
            result.put("success", true);
            result.put("message", message);
        } catch (Exception e) {
            testResult = "Failed";
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        SenderMailbox update = new SenderMailbox();
        update.setId(id);
        update.setLastTestedAt(LocalDateTime.now());
        update.setLastTestResult(testResult);
        mailboxMapper.updateById(update);
        result.put("testedAt", LocalDateTime.now().toString());
        return result;
    }

    public Map<String, String> buildAvailableSmtpConfig(Long id) {
        return buildSmtpConfig(requireAvailableMailbox(id), true);
    }

    public SenderMailbox requireAvailableMailbox(Long id) {
        SenderMailbox mailbox = requireMailbox(id);
        if (!isAvailable(mailbox)) {
            throw new BizException("发件箱已删除、停用或 SMTP 配置不完整");
        }
        return mailbox;
    }

    public Map<String, String> buildSmtpConfig(SenderMailbox mailbox, boolean requireAvailable) {
        if (mailbox == null) {
            throw new BizException("发件箱不存在");
        }
        if (requireAvailable && !isAvailable(mailbox)) {
            throw new BizException("发件箱已删除、停用或 SMTP 配置不完整");
        }
        String password;
        try {
            password = cryptoService.decryptIfNeeded(mailbox.getPassword());
        } catch (Exception e) {
            throw new BizException("发件箱密码解密失败，请重新保存该发件箱");
        }
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("host", trim(mailbox.getSmtpServer()));
        cfg.put("port", mailbox.getSmtpPort() == null ? "" : String.valueOf(mailbox.getSmtpPort()));
        cfg.put("username", trim(mailbox.getUsername()));
        cfg.put("password", password);
        cfg.put("useSsl", mailbox.getUseSsl() == null || mailbox.getUseSsl() == 1 ? "true" : "false");
        cfg.put("fromAddress", defaultIfBlank(mailbox.getFromAddress(), mailbox.getUsername()));
        cfg.put("fromName", defaultIfBlank(mailbox.getFromName(), "员工认可管理平台"));
        cfg.put("testRecipientWhitelist", trim(mailbox.getTestRecipientWhitelist()));
        cfg.put("emailBlacklist", trim(mailbox.getEmailBlacklist()));
        cfg.put("emailWhitelist", trim(mailbox.getEmailWhitelist()));
        return cfg;
    }

    private SenderMailbox requireMailbox(Long id) {
        if (id == null) {
            throw new BizException("发件箱 ID 不能为空");
        }
        SenderMailbox mb = mailboxMapper.selectById(id);
        if (mb == null) {
            throw new BizException("发件箱不存在");
        }
        return mb;
    }

    private SenderMailboxResponse toResponse(SenderMailbox mailbox) {
        return new SenderMailboxResponse(
                mailbox.getId(),
                mailbox.getName(),
                mailbox.getSmtpServer(),
                mailbox.getSmtpPort(),
                mailbox.getUsername(),
                mailbox.getUseSsl(),
                mailbox.getIsDefault(),
                mailbox.getStatus(),
                defaultIfBlank(mailbox.getFromAddress(), mailbox.getUsername()),
                defaultIfBlank(mailbox.getFromName(), "员工认可管理平台"),
                mailbox.getTestRecipientWhitelist(),
                mailbox.getEmailBlacklist(),
                mailbox.getEmailWhitelist(),
                mailbox.getLastTestedAt(),
                mailbox.getLastTestResult(),
                mailbox.getCreatedAt(),
                mailbox.getUpdatedAt());
    }

    private boolean isAvailable(SenderMailbox mailbox) {
        return mailbox != null
                && (mailbox.getDeleted() == null || mailbox.getDeleted() == 0)
                && STATUS_ACTIVE.equals(mailbox.getStatus())
                && !trim(mailbox.getSmtpServer()).isBlank()
                && mailbox.getSmtpPort() != null
                && mailbox.getSmtpPort() > 0
                && mailbox.getSmtpPort() <= 65535
                && !trim(mailbox.getUsername()).isBlank()
                && !trim(mailbox.getPassword()).isBlank();
    }

    private void validate(SenderMailbox mailbox, boolean requirePassword) {
        if (trim(mailbox.getName()).isBlank()) {
            throw new BizException("发件箱名称不能为空");
        }
        if (trim(mailbox.getSmtpServer()).isBlank()) {
            throw new BizException("SMTP 服务器不能为空");
        }
        if (mailbox.getSmtpPort() == null || mailbox.getSmtpPort() <= 0 || mailbox.getSmtpPort() > 65535) {
            throw new BizException("SMTP 端口不合法");
        }
        if (trim(mailbox.getUsername()).isBlank()) {
            throw new BizException("SMTP 用户名不能为空");
        }
        if (requirePassword && trim(mailbox.getPassword()).isBlank()) {
            throw new BizException("SMTP 密码不能为空");
        }
        if (!STATUS_ACTIVE.equals(mailbox.getStatus()) && !STATUS_INACTIVE.equals(mailbox.getStatus())) {
            throw new BizException("发件箱状态仅支持 Active/Inactive");
        }
    }

    private SenderMailbox copyOf(SenderMailbox source) {
        SenderMailbox target = new SenderMailbox();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setSmtpServer(source.getSmtpServer());
        target.setSmtpPort(source.getSmtpPort());
        target.setUsername(source.getUsername());
        target.setPassword(source.getPassword());
        target.setUseSsl(source.getUseSsl());
        target.setIsDefault(source.getIsDefault());
        target.setStatus(source.getStatus() == null ? STATUS_ACTIVE : source.getStatus());
        target.setFromAddress(source.getFromAddress());
        target.setFromName(source.getFromName());
        target.setTestRecipientWhitelist(source.getTestRecipientWhitelist());
        target.setEmailBlacklist(source.getEmailBlacklist());
        target.setEmailWhitelist(source.getEmailWhitelist());
        return target;
    }

    private String normalizeStatus(String status) {
        String normalized = trim(status);
        if (normalized.isBlank()) {
            return STATUS_ACTIVE;
        }
        if (STATUS_ACTIVE.equalsIgnoreCase(normalized)) {
            return STATUS_ACTIVE;
        }
        if (STATUS_INACTIVE.equalsIgnoreCase(normalized)) {
            return STATUS_INACTIVE;
        }
        throw new BizException("发件箱状态仅支持 Active/Inactive");
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = trim(value);
        return normalized.isBlank() ? trim(fallback) : normalized;
    }

    private String trimToNull(String value) {
        String normalized = trim(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
