-- Store all active SuccessFactors assignments while preserving one sys_user per person.
-- MySQL 8.x. Safe to run more than once. This project executes schema scripts manually.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS md_employee_assignment (
  id bigint NOT NULL AUTO_INCREMENT,
  sys_user_id bigint NOT NULL COMMENT 'Person-level sys_user id.',
  employee_id varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SF personIdExternal.',
  sf_user_id varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SF User.userId assignment identity.',
  is_primary_assignment tinyint NOT NULL DEFAULT 0,
  name varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  email varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  phone varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  department varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  country varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  company_name varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  job_title varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  position_code varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  division varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  third_department varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  fourth_department varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  fifth_department varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  location varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  employee_type varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  assignment_class varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ST=Home, GA=Host.',
  management_job_level varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  professional_job_level varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  job_grade varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  date_of_birth date DEFAULT NULL,
  hire_date date DEFAULT NULL,
  benefits_eligibility_start_date date DEFAULT NULL,
  contract_end_date date DEFAULT NULL,
  probation_end_date date DEFAULT NULL,
  source_type varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  source_active tinyint NOT NULL DEFAULT 1 COMMENT 'Present in the latest successful source sync.',
  synced_at datetime DEFAULT NULL,
  deleted tinyint NOT NULL DEFAULT 0,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_md_employee_assignment_source_user (source_type, sf_user_id),
  KEY idx_md_employee_assignment_person (sys_user_id, source_active, is_primary_assignment),
  KEY idx_md_employee_assignment_employee (employee_id, source_active, assignment_class),
  KEY idx_md_employee_assignment_deleted (deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='SuccessFactors employment assignments; one person may have multiple rows.';

-- Compatibility seed: current sys_user values remain visible before the first full multi-assignment sync.
INSERT INTO md_employee_assignment (
  sys_user_id, employee_id, sf_user_id, is_primary_assignment,
  name, email, phone, department, country, company_name, job_title, position_code,
  division, third_department, fourth_department, fifth_department, location,
  employee_type, assignment_class, management_job_level, professional_job_level, job_grade,
  date_of_birth, hire_date, benefits_eligibility_start_date, contract_end_date, probation_end_date,
  source_type, source_active, synced_at, deleted
)
SELECT
  u.id, u.employee_id, CONCAT('LEGACY:', u.employee_id), 1,
  u.name, u.email, u.phone, u.department, u.country, u.company_name, u.job_title, u.position_code,
  u.division, u.third_department, u.fourth_department, u.fifth_department, u.location,
  u.employee_type, u.assignment_class, u.management_job_level, u.professional_job_level, u.job_grade,
  u.date_of_birth, u.hire_date, u.benefits_eligibility_start_date, u.contract_end_date, u.probation_end_date,
  COALESCE(NULLIF(u.source_type, ''), 'Legacy'), 1, u.synced_at, 0
FROM sys_user u
WHERE u.deleted = 0
  AND u.employee_id IS NOT NULL
  AND u.employee_id <> ''
ON DUPLICATE KEY UPDATE
  sys_user_id = VALUES(sys_user_id),
  employee_id = VALUES(employee_id),
  source_active = VALUES(source_active),
  deleted = 0,
  updated_at = CURRENT_TIMESTAMP;

UPDATE cfg_query_config
SET query_path = '/odata/v2/User?$format=json&$filter=status eq ''t''&$select=userId,isPrimaryAssignment,dateOfBirth,empInfo/benefitsEligibilityStartDate,empInfo/assignmentClass,personKeyNav/personIdExternal,nickname,email,jobTitle,hireDate,empInfo/jobInfoNav/company,empInfo/jobInfoNav/countryOfCompany,empInfo/jobInfoNav/department,empInfo/jobInfoNav/position,empInfo/jobInfoNav/division,empInfo/jobInfoNav/customString2,empInfo/jobInfoNav/customString12,empInfo/jobInfoNav/customString13,empInfo/jobInfoNav/location,empInfo/jobInfoNav/employeeTypeNav/externalCode,empInfo/jobInfoNav/contractEndDate,empInfo/jobInfoNav/customDate3,empInfo/jobInfoNav/customString10,empInfo/jobInfoNav/customString15,empInfo/jobInfoNav/customString47&$expand=personKeyNav,empInfo/jobInfoNav/employeeTypeNav',
    description = 'SuccessFactors active User assignments grouped by personIdExternal; includes primary, Home and Host profiles',
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'SF OData Primary Users'
  AND deleted = 0;
