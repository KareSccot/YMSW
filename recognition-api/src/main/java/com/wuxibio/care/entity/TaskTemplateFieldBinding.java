package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("task_template_field_binding")
public class TaskTemplateFieldBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_template_id")
    private Long taskTemplateId;

    @TableField("field_registry_id")
    private Long fieldRegistryId;

    @TableField("required_flag")
    private Integer requiredFlag;

    @TableField("missing_policy")
    private String missingPolicy;

    @TableField("default_value")
    private String defaultValue;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskTemplateId() { return taskTemplateId; }
    public void setTaskTemplateId(Long taskTemplateId) { this.taskTemplateId = taskTemplateId; }
    public Long getFieldRegistryId() { return fieldRegistryId; }
    public void setFieldRegistryId(Long fieldRegistryId) { this.fieldRegistryId = fieldRegistryId; }
    public Integer getRequiredFlag() { return requiredFlag; }
    public void setRequiredFlag(Integer requiredFlag) { this.requiredFlag = requiredFlag; }
    public String getMissingPolicy() { return missingPolicy; }
    public void setMissingPolicy(String missingPolicy) { this.missingPolicy = missingPolicy; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
}
