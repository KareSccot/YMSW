package com.wuxibio.care.dto;

import com.wuxibio.care.entity.ExternalConnection;

import java.time.LocalDateTime;

public class ExternalConnectionResponse {

    private Long id;
    private String type;
    private String name;
    private String environment;
    private String config;
    private Integer isActive;
    private String status;
    private LocalDateTime lastTestedAt;
    private String lastTestResult;
    private Long createdBy;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ExternalConnectionResponse from(ExternalConnection connection, String maskedConfig) {
        ExternalConnectionResponse response = new ExternalConnectionResponse();
        response.setId(connection.getId());
        response.setType(connection.getType());
        response.setName(connection.getName());
        response.setEnvironment(connection.getEnvironment());
        response.setConfig(maskedConfig);
        response.setIsActive(connection.getIsActive());
        response.setStatus(connection.getStatus());
        response.setLastTestedAt(connection.getLastTestedAt());
        response.setLastTestResult(connection.getLastTestResult());
        response.setCreatedBy(connection.getCreatedBy());
        response.setDeleted(connection.getDeleted());
        response.setCreatedAt(connection.getCreatedAt());
        response.setUpdatedAt(connection.getUpdatedAt());
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public String getLastTestResult() { return lastTestResult; }
    public void setLastTestResult(String lastTestResult) { this.lastTestResult = lastTestResult; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
