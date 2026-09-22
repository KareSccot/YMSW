package com.wuxibio.care.service;

import com.wuxibio.care.entity.SysUser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@Service
public class ConditionRuleAudienceExportService {

    private final ConditionRuleService conditionRuleService;

    public ConditionRuleAudienceExportService(ConditionRuleService conditionRuleService) {
        this.conditionRuleService = conditionRuleService;
    }

    public ExportDescriptor export(Long versionId, LocalDate evaluationDate, OutputStream outputStream) throws IOException {
        return export(versionId, evaluationDate, outputStream, Locale.SIMPLIFIED_CHINESE);
    }

    public ExportDescriptor export(
            Long versionId,
            LocalDate evaluationDate,
            OutputStream outputStream,
            Locale locale) throws IOException {
        ConditionRuleService.AudienceExportData data =
                conditionRuleService.buildAccessibleAudienceExport(versionId, evaluationDate);
        boolean english = isEnglish(locale);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(english ? "Matched Employees" : "命中人员");
            CellStyle headerStyle = headerStyle(workbook);
            List<Column> columns = columns(english);
            Row header = sheet.createRow(0);
            for (int index = 0; index < columns.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(columns.get(index).label());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, columns.get(index).width() * 256);
            }
            int rowIndex = 1;
            for (SysUser user : data.employees()) {
                Row row = sheet.createRow(rowIndex++);
                for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                    String value = columns.get(columnIndex).extractor().apply(user);
                    row.createCell(columnIndex).setCellValue(safeCell(value));
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, Math.max(0, data.employees().size()), 0, columns.size() - 1));
            workbook.write(outputStream);
            workbook.dispose();
        }
        String baseName = sanitizeFileName(data.rule().ruleName());
        String filename = baseName + "_v" + data.rule().versionNo() + "_" + data.evaluationDate() + ".xlsx";
        return new ExportDescriptor(filename, data.employees().size());
    }

    public String filename(Long versionId, LocalDate evaluationDate) {
        ConditionRuleService.RuleVersionView rule = conditionRuleService.requireAccessiblePublishedVersion(versionId);
        LocalDate date = evaluationDate == null ? LocalDate.now() : evaluationDate;
        return sanitizeFileName(rule.ruleName()) + "_v" + rule.versionNo() + "_" + date + ".xlsx";
    }

    private CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private List<Column> columns(boolean english) {
        return List.of(
                new Column(label(english, "工号", "Employee ID"), 16, SysUser::getEmployeeId),
                new Column(label(english, "姓名", "Name"), 18, SysUser::getName),
                new Column(label(english, "邮箱", "Email"), 30, SysUser::getEmail),
                new Column(label(english, "公司", "Company"), 24,
                        user -> display(user.getCompanyNameDisplay(), user.getCompanyName())),
                new Column(label(english, "一级组织（事业部）", "Level 1 Organization (Division)"), 28,
                        user -> display(user.getDivisionDisplay(), user.getDivision())),
                new Column(label(english, "二级组织（部门）", "Level 2 Organization (Department)"), 30,
                        user -> display(user.getDepartmentDisplay(), user.getDepartment())),
                new Column(label(english, "三级组织", "Level 3 Organization"), 28,
                        user -> display(user.getThirdDepartmentDisplay(), user.getThirdDepartment())),
                new Column(label(english, "四级组织", "Level 4 Organization"), 28,
                        user -> display(user.getFourthDepartmentDisplay(), user.getFourthDepartment())),
                new Column(label(english, "五级组织", "Level 5 Organization"), 28,
                        user -> display(user.getFifthDepartmentDisplay(), user.getFifthDepartment())),
                new Column(label(english, "职位", "Position"), 26,
                        user -> display(user.getPositionDisplay(), first(user.getPositionCode(), user.getJobTitle()))),
                new Column(label(english, "国家/地区", "Country/Region"), 18,
                        user -> display(user.getCountryDisplay(), user.getCountry())),
                new Column(label(english, "办公地点", "Location"), 20,
                        user -> display(user.getLocationDisplay(), user.getLocation())),
                new Column(label(english, "员工类型", "Employee Type"), 18,
                        user -> display(user.getEmployeeTypeDisplay(), user.getEmployeeType())),
                new Column(label(english, "人员类型", "Assignment Class"), 18,
                        user -> display(user.getAssignmentClassDisplay(), user.getAssignmentClass())),
                new Column(label(english, "管理岗位级别", "Management Job Level"), 22,
                        user -> display(user.getManagementJobLevelDisplay(), user.getManagementJobLevel())),
                new Column(label(english, "专业岗位级别", "Professional Job Level"), 22,
                        user -> display(user.getProfessionalJobLevelDisplay(), user.getProfessionalJobLevel())),
                new Column(label(english, "职位等级", "Job Grade"), 18,
                        user -> display(user.getJobGradeDisplay(), user.getJobGrade())),
                new Column(label(english, "出生日期", "Date of Birth"), 16, user -> date(user.getDateOfBirth())),
                new Column(label(english, "入职日期", "Hire Date"), 14, user -> date(user.getHireDate())),
                new Column(label(english, "福利资格开始日期", "Benefits Eligibility Start Date"), 24,
                        user -> date(user.getBenefitsEligibilityStartDate())),
                new Column(label(english, "合同结束日期", "Contract End Date"), 18,
                        user -> date(user.getContractEndDate())),
                new Column(label(english, "试用期结束日期", "Probation End Date"), 18,
                        user -> date(user.getProbationEndDate())),
                new Column(label(english, "钉钉 User ID", "DingTalk User ID"), 22, SysUser::getDingtalkUserId),
                new Column(label(english, "状态", "Status"), 12,
                        user -> localizedStatus(user.getStatus(), english)));
    }

    private String label(boolean english, String chinese, String englishText) {
        return english ? englishText : chinese;
    }

    private String localizedStatus(String value, boolean english) {
        if (value == null || value.isBlank()) return "";
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> english ? "Active" : "启用";
            case "INACTIVE" -> english ? "Inactive" : "停用";
            case "SYNCED" -> english ? "Synced" : "已同步";
            default -> value.trim();
        };
    }

    private boolean isEnglish(Locale locale) {
        return locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
    }

    private String safeCell(String value) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0) {
            return "'" + normalized;
        }
        return normalized;
    }

    private String display(String display, String raw) {
        return display == null || display.isBlank() ? nullToEmpty(raw) : display;
    }

    private String first(String primary, String fallback) {
        return primary == null || primary.isBlank() ? nullToEmpty(fallback) : primary;
    }

    private String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeFileName(String value) {
        String normalized = value == null || value.isBlank() ? "audience" : value.trim();
        return normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private record Column(String label, int width, Function<SysUser, String> extractor) {}

    public record ExportDescriptor(String filename, int rowCount) {}
}
