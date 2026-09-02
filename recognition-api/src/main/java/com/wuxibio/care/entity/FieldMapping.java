package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("cfg_field_mapping")
public class FieldMapping {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long queryConfigId;
    private String sourceField;
    private String tokenKey;
    private String label;
    private String fieldType;
    private Integer isBuiltin;
    private Integer sortOrder;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQueryConfigId() { return queryConfigId; }
    public void setQueryConfigId(Long queryConfigId) { this.queryConfigId = queryConfigId; }
    public String getSourceField() { return sourceField; }
    public void setSourceField(String sourceField) { this.sourceField = sourceField; }

    /** @deprecated Use {@link #getSourceField()} instead. Kept for backward compatibility in OdataService. */
    @Deprecated
    public String getOdataField() { return sourceField; }
    /** @deprecated Use {@link #setSourceField(String)} instead. Kept for backward compatibility in OdataService. */
    @Deprecated
    public void setOdataField(String odataField) { this.sourceField = odataField; }

    public String getTokenKey() { return tokenKey; }
    public void setTokenKey(String tokenKey) { this.tokenKey = tokenKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }
    public Integer getIsBuiltin() { return isBuiltin; }
    public void setIsBuiltin(Integer isBuiltin) { this.isBuiltin = isBuiltin; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
