package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("cfg_query_config")
public class QueryConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String configType;
    private Long connectionId;
    private String queryPath;
    private String requestParams;
    private String employeeIdField;
    private String description;
    private Integer isActive;
    private Long createdBy;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConfigType() { return configType; }
    public void setConfigType(String configType) { this.configType = configType; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }
    public String getQueryPath() { return queryPath; }
    public void setQueryPath(String queryPath) { this.queryPath = queryPath; }
    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }
    public String getEmployeeIdField() { return employeeIdField; }
    public void setEmployeeIdField(String employeeIdField) { this.employeeIdField = employeeIdField; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getIsActive() { return isActive; }
    public void setIsActive(Integer isActive) { this.isActive = isActive; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** @deprecated Use {@link #getQueryPath()} instead. Kept for backward compatibility. */
    @Deprecated
    public String getOdataUrl() { return queryPath; }

    /** @deprecated Use {@link #setQueryPath(String)} instead. Kept for backward compatibility. */
    @Deprecated
    public void setOdataUrl(String odataUrl) { this.queryPath = odataUrl; }
}
