package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;

@TableName("target_group_condition")
public class TargetGroupCondition {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long targetGroupId;
    private String dimension;
    private String value;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTargetGroupId() { return targetGroupId; }
    public void setTargetGroupId(Long targetGroupId) { this.targetGroupId = targetGroupId; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
