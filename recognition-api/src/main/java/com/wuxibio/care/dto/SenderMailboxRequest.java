package com.wuxibio.care.dto;

public class SenderMailboxRequest {
    private String name;
    private String smtpServer;
    private Integer smtpPort;
    private String username;
    private String password;
    private Integer useSsl;
    private String status;
    private String fromAddress;
    private String fromName;
    private String testRecipientWhitelist;
    private String emailBlacklist;
    private String emailWhitelist;

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
}
