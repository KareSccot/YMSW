package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * sys_user — unified user / employee master record.
 *
 * Two semantic shapes share this table (distinguished by {@link #sourceType} and
 * whether {@link #password} is set):
 *  - Login user: created by admin; has username + password + role bindings.
 *  - Synced employee: written by MasterDataSyncService from OData/HRDC; password
 *    is NULL; auto-attached to the "Employee" role on first insert. On subsequent
 *    syncs only the data columns are refreshed — role bindings are left alone.
 */
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String name;
    private String email;
    private String phone;
    private String department;
    private String country;
    private String companyName;
    @TableField(exist = false)
    private String companyNameDisplay;
    @TableField(exist = false)
    private String departmentDisplay;
    @TableField(exist = false)
    private String countryDisplay;
    private String jobTitle;
    private String positionCode;
    @TableField(exist = false)
    private String positionDisplay;
    private String division;
    @TableField(exist = false)
    private String divisionDisplay;
    private String thirdDepartment;
    @TableField(exist = false)
    private String thirdDepartmentDisplay;
    private String fourthDepartment;
    @TableField(exist = false)
    private String fourthDepartmentDisplay;
    private String fifthDepartment;
    @TableField(exist = false)
    private String fifthDepartmentDisplay;
    private String location;
    @TableField(exist = false)
    private String locationDisplay;
    private String employeeType;
    @TableField(exist = false)
    private String employeeTypeDisplay;
    private LocalDate hireDate;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private String sourceType;
    private LocalDateTime syncedAt;
    private String employeeId;
    private String dingtalkUserId;
    private String status;
    private Integer loginFailCount;
    private LocalDateTime lockedUntil;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getCompanyNameDisplay() { return companyNameDisplay; }
    public void setCompanyNameDisplay(String companyNameDisplay) { this.companyNameDisplay = companyNameDisplay; }
    public String getDepartmentDisplay() { return departmentDisplay; }
    public void setDepartmentDisplay(String departmentDisplay) { this.departmentDisplay = departmentDisplay; }
    public String getCountryDisplay() { return countryDisplay; }
    public void setCountryDisplay(String countryDisplay) { this.countryDisplay = countryDisplay; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getPositionCode() { return positionCode; }
    public void setPositionCode(String positionCode) { this.positionCode = positionCode; }
    public String getPositionDisplay() { return positionDisplay; }
    public void setPositionDisplay(String positionDisplay) { this.positionDisplay = positionDisplay; }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
    public String getDivisionDisplay() { return divisionDisplay; }
    public void setDivisionDisplay(String divisionDisplay) { this.divisionDisplay = divisionDisplay; }
    public String getThirdDepartment() { return thirdDepartment; }
    public void setThirdDepartment(String thirdDepartment) { this.thirdDepartment = thirdDepartment; }
    public String getThirdDepartmentDisplay() { return thirdDepartmentDisplay; }
    public void setThirdDepartmentDisplay(String thirdDepartmentDisplay) { this.thirdDepartmentDisplay = thirdDepartmentDisplay; }
    public String getFourthDepartment() { return fourthDepartment; }
    public void setFourthDepartment(String fourthDepartment) { this.fourthDepartment = fourthDepartment; }
    public String getFourthDepartmentDisplay() { return fourthDepartmentDisplay; }
    public void setFourthDepartmentDisplay(String fourthDepartmentDisplay) { this.fourthDepartmentDisplay = fourthDepartmentDisplay; }
    public String getFifthDepartment() { return fifthDepartment; }
    public void setFifthDepartment(String fifthDepartment) { this.fifthDepartment = fifthDepartment; }
    public String getFifthDepartmentDisplay() { return fifthDepartmentDisplay; }
    public void setFifthDepartmentDisplay(String fifthDepartmentDisplay) { this.fifthDepartmentDisplay = fifthDepartmentDisplay; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getLocationDisplay() { return locationDisplay; }
    public void setLocationDisplay(String locationDisplay) { this.locationDisplay = locationDisplay; }
    public String getEmployeeType() { return employeeType; }
    public void setEmployeeType(String employeeType) { this.employeeType = employeeType; }
    public String getEmployeeTypeDisplay() { return employeeTypeDisplay; }
    public void setEmployeeTypeDisplay(String employeeTypeDisplay) { this.employeeTypeDisplay = employeeTypeDisplay; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public LocalDate getContractEndDate() { return contractEndDate; }
    public void setContractEndDate(LocalDate contractEndDate) { this.contractEndDate = contractEndDate; }
    public LocalDate getProbationEndDate() { return probationEndDate; }
    public void setProbationEndDate(LocalDate probationEndDate) { this.probationEndDate = probationEndDate; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public String getDingtalkUserId() { return dingtalkUserId; }
    public void setDingtalkUserId(String dingtalkUserId) { this.dingtalkUserId = dingtalkUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getLoginFailCount() { return loginFailCount; }
    public void setLoginFailCount(Integer loginFailCount) { this.loginFailCount = loginFailCount; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
