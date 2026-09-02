package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@TableName("sender_mailbox")
public class SenderMailbox {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String smtpServer;
    private Integer smtpPort;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private Integer useSsl;
    private Integer isDefault;
    private String status;
    private String fromAddress;
    private String fromName;
    private String testRecipientWhitelist;
    private String emailBlacklist;
    private String emailWhitelist;
    private LocalDateTime lastTestedAt;
    private String lastTestResult;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSmtpServer() { return smtpServer; }
    public void setSmtpServer(String smtpServer) { this.smtpServer = smtpServer; }
    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer smtpPort) { this.smtpPort = smtpPort; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getUseSsl() { return useSsl; }
    public void setUseSsl(Integer useSsl) { this.useSsl = useSsl; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getTestRecipientWhitelist() { return testRecipientWhitelist; }
    public void setTestRecipientWhitelist(String testRecipientWhitelist) { this.testRecipientWhitelist = testRecipientWhitelist; }
    public String getEmailBlacklist() { return emailBlacklist; }
    public void setEmailBlacklist(String emailBlacklist) { this.emailBlacklist = emailBlacklist; }
    public String getEmailWhitelist() { return emailWhitelist; }
    public void setEmailWhitelist(String emailWhitelist) { this.emailWhitelist = emailWhitelist; }
    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastTestResult() { return lastTestResult; }
    public void setLastTestResult(String lastTestResult) { this.lastTestResult = lastTestResult; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
