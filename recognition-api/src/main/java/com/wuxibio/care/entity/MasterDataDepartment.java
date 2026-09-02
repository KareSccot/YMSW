package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("md_department")
public class MasterDataDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String externalCode;
    private String startDate;
    private String nameZhCn;
    private String nameEnUs;
    private String parentExternalCode;
    private String status;
    private String sourceType;
    private LocalDateTime syncedAt;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExternalCode() { return externalCode; }
    public void setExternalCode(String externalCode) { this.externalCode = externalCode; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getNameZhCn() { return nameZhCn; }
    public void setNameZhCn(String nameZhCn) { this.nameZhCn = nameZhCn; }
    public String getNameEnUs() { return nameEnUs; }
    public void setNameEnUs(String nameEnUs) { this.nameEnUs = nameEnUs; }
    public String getParentExternalCode() { return parentExternalCode; }
    public void setParentExternalCode(String parentExternalCode) { this.parentExternalCode = parentExternalCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
