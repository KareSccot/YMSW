package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("cfg_field_registry")
public class FieldRegistry {

    @TableId(value = "field_registry_id", type = IdType.AUTO)
    private Long id;

    @TableField("field_code")
    private String code;

    @TableField("field_name")
    private String name;

    @TableField("source_type")
    private String sourceType;

    @TableField("data_type")
    private String dataType;

    private String description;

    @TableField("sample_value")
    private String sampleValue;

    @TableField("default_missing_policy")
    private String missingPolicy;

    @TableField("default_value")
    private String defaultValue;

    @TableField("source_binding_definition")
    private String sourceBindingDefinition;

    private String status;

    @TableField("effective_start_date")
    private LocalDate effectiveStartDate;

    @TableField("effective_end_date")
    private LocalDate effectiveEndDate;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSampleValue() { return sampleValue; }
    public void setSampleValue(String sampleValue) { this.sampleValue = sampleValue; }
    public String getMissingPolicy() { return missingPolicy; }
    public void setMissingPolicy(String missingPolicy) { this.missingPolicy = missingPolicy; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public String getSourceBindingDefinition() { return sourceBindingDefinition; }
    public void setSourceBindingDefinition(String sourceBindingDefinition) { this.sourceBindingDefinition = sourceBindingDefinition; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getEffectiveStartDate() { return effectiveStartDate; }
    public void setEffectiveStartDate(LocalDate effectiveStartDate) { this.effectiveStartDate = effectiveStartDate; }
    public LocalDate getEffectiveEndDate() { return effectiveEndDate; }
    public void setEffectiveEndDate(LocalDate effectiveEndDate) { this.effectiveEndDate = effectiveEndDate; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
