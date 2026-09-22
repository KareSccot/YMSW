package com.wuxibio.care.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** SuccessFactors employment assignment linked to one person-level {@code sys_user}. */
@TableName("md_employee_assignment")
public class EmployeeAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sysUserId;
    private String employeeId;
    private String sfUserId;
    private Integer isPrimaryAssignment;
    private String name;
    private String email;
    private String phone;
    private String department;
    private String country;
    private String companyName;
    private String jobTitle;
    private String positionCode;
    private String division;
    private String thirdDepartment;
    private String fourthDepartment;
    private String fifthDepartment;
    private String location;
    private String employeeType;
    private String assignmentClass;
    private String managementJobLevel;
    private String professionalJobLevel;
    private String jobGrade;
    private LocalDate dateOfBirth;
    private LocalDate hireDate;
    private LocalDate benefitsEligibilityStartDate;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private String sourceType;
    private Integer sourceActive;
    private LocalDateTime syncedAt;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(exist = false) private String companyNameDisplay;
    @TableField(exist = false) private String departmentDisplay;
    @TableField(exist = false) private String countryDisplay;
    @TableField(exist = false) private String positionDisplay;
    @TableField(exist = false) private String divisionDisplay;
    @TableField(exist = false) private String thirdDepartmentDisplay;
    @TableField(exist = false) private String fourthDepartmentDisplay;
    @TableField(exist = false) private String fifthDepartmentDisplay;
    @TableField(exist = false) private String locationDisplay;
    @TableField(exist = false) private String employeeTypeDisplay;
    @TableField(exist = false) private String assignmentClassDisplay;
    @TableField(exist = false) private String managementJobLevelDisplay;
    @TableField(exist = false) private String professionalJobLevelDisplay;
    @TableField(exist = false) private String jobGradeDisplay;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSysUserId() { return sysUserId; }
    public void setSysUserId(Long sysUserId) { this.sysUserId = sysUserId; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }
    public String getSfUserId() { return sfUserId; }
    public void setSfUserId(String sfUserId) { this.sfUserId = sfUserId; }
    public Integer getIsPrimaryAssignment() { return isPrimaryAssignment; }
    public void setIsPrimaryAssignment(Integer isPrimaryAssignment) { this.isPrimaryAssignment = isPrimaryAssignment; }
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
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }
    public String getPositionCode() { return positionCode; }
    public void setPositionCode(String positionCode) { this.positionCode = positionCode; }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
    public String getThirdDepartment() { return thirdDepartment; }
    public void setThirdDepartment(String thirdDepartment) { this.thirdDepartment = thirdDepartment; }
    public String getFourthDepartment() { return fourthDepartment; }
    public void setFourthDepartment(String fourthDepartment) { this.fourthDepartment = fourthDepartment; }
    public String getFifthDepartment() { return fifthDepartment; }
    public void setFifthDepartment(String fifthDepartment) { this.fifthDepartment = fifthDepartment; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getEmployeeType() { return employeeType; }
    public void setEmployeeType(String employeeType) { this.employeeType = employeeType; }
    public String getAssignmentClass() { return assignmentClass; }
    public void setAssignmentClass(String assignmentClass) { this.assignmentClass = assignmentClass; }
    public String getManagementJobLevel() { return managementJobLevel; }
    public void setManagementJobLevel(String managementJobLevel) { this.managementJobLevel = managementJobLevel; }
    public String getProfessionalJobLevel() { return professionalJobLevel; }
    public void setProfessionalJobLevel(String professionalJobLevel) { this.professionalJobLevel = professionalJobLevel; }
    public String getJobGrade() { return jobGrade; }
    public void setJobGrade(String jobGrade) { this.jobGrade = jobGrade; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public LocalDate getHireDate() { return hireDate; }
    public void setHireDate(LocalDate hireDate) { this.hireDate = hireDate; }
    public LocalDate getBenefitsEligibilityStartDate() { return benefitsEligibilityStartDate; }
    public void setBenefitsEligibilityStartDate(LocalDate benefitsEligibilityStartDate) { this.benefitsEligibilityStartDate = benefitsEligibilityStartDate; }
    public LocalDate getContractEndDate() { return contractEndDate; }
    public void setContractEndDate(LocalDate contractEndDate) { this.contractEndDate = contractEndDate; }
    public LocalDate getProbationEndDate() { return probationEndDate; }
    public void setProbationEndDate(LocalDate probationEndDate) { this.probationEndDate = probationEndDate; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Integer getSourceActive() { return sourceActive; }
    public void setSourceActive(Integer sourceActive) { this.sourceActive = sourceActive; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getCompanyNameDisplay() { return companyNameDisplay; }
    public void setCompanyNameDisplay(String value) { this.companyNameDisplay = value; }
    public String getDepartmentDisplay() { return departmentDisplay; }
    public void setDepartmentDisplay(String value) { this.departmentDisplay = value; }
    public String getCountryDisplay() { return countryDisplay; }
    public void setCountryDisplay(String value) { this.countryDisplay = value; }
    public String getPositionDisplay() { return positionDisplay; }
    public void setPositionDisplay(String value) { this.positionDisplay = value; }
    public String getDivisionDisplay() { return divisionDisplay; }
    public void setDivisionDisplay(String value) { this.divisionDisplay = value; }
    public String getThirdDepartmentDisplay() { return thirdDepartmentDisplay; }
    public void setThirdDepartmentDisplay(String value) { this.thirdDepartmentDisplay = value; }
    public String getFourthDepartmentDisplay() { return fourthDepartmentDisplay; }
    public void setFourthDepartmentDisplay(String value) { this.fourthDepartmentDisplay = value; }
    public String getFifthDepartmentDisplay() { return fifthDepartmentDisplay; }
    public void setFifthDepartmentDisplay(String value) { this.fifthDepartmentDisplay = value; }
    public String getLocationDisplay() { return locationDisplay; }
    public void setLocationDisplay(String value) { this.locationDisplay = value; }
    public String getEmployeeTypeDisplay() { return employeeTypeDisplay; }
    public void setEmployeeTypeDisplay(String value) { this.employeeTypeDisplay = value; }
    public String getAssignmentClassDisplay() { return assignmentClassDisplay; }
    public void setAssignmentClassDisplay(String value) { this.assignmentClassDisplay = value; }
    public String getManagementJobLevelDisplay() { return managementJobLevelDisplay; }
    public void setManagementJobLevelDisplay(String value) { this.managementJobLevelDisplay = value; }
    public String getProfessionalJobLevelDisplay() { return professionalJobLevelDisplay; }
    public void setProfessionalJobLevelDisplay(String value) { this.professionalJobLevelDisplay = value; }
    public String getJobGradeDisplay() { return jobGradeDisplay; }
    public void setJobGradeDisplay(String value) { this.jobGradeDisplay = value; }
}
