package com.wuxibio.care.service;

import com.wuxibio.care.entity.SysUser;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionRuleAudienceExportServiceTest {

    @Test
    void exportsAllMatchedRowsAndEscapesFormulaLikeValues() throws Exception {
        ConditionRuleService conditionRuleService = mock(ConditionRuleService.class);
        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                9L, 8L, "RULE", "Anniversary", "Active", 3, "Published", "{}", "summary",
                List.of(), 1L, 1L, null, null, null);
        SysUser first = user("E001", "Alice");
        first.setCompanyName("2000");
        first.setCompanyNameDisplay("药明生物");
        SysUser second = user("=1+1", "Bob");
        LocalDate date = LocalDate.of(2026, 8, 20);
        when(conditionRuleService.buildAccessibleAudienceExport(9L, date))
                .thenReturn(new ConditionRuleService.AudienceExportData(rule, date, List.of(first, second)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConditionRuleAudienceExportService.ExportDescriptor descriptor =
                new ConditionRuleAudienceExportService(conditionRuleService).export(9L, date, output);

        assertThat(descriptor.rowCount()).isEqualTo(2);
        assertThat(descriptor.filename()).isEqualTo("Anniversary_v3_2026-08-20.xlsx");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("命中人员");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("工号");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("一级组织（事业部）");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("药明生物");
            assertThat(sheet.getRow(1).getCell(columnIndex(sheet, "状态")).getStringCellValue())
                    .isEqualTo("启用");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("'=1+1");
        }
    }

    @Test
    void exportsEnglishSheetHeadersAndLocalizedData() throws Exception {
        ConditionRuleService conditionRuleService = mock(ConditionRuleService.class);
        ConditionRuleService.RuleVersionView rule = new ConditionRuleService.RuleVersionView(
                9L, 8L, "RULE", "Anniversary", "Active", 3, "Published", "{}", "summary",
                List.of(), 1L, 1L, null, null, null);
        SysUser employee = user("E001", "Alice");
        employee.setCompanyName("2000");
        employee.setCompanyNameDisplay("WuXi Biologics");
        employee.setDivision("DIV01");
        employee.setDivisionDisplay("Biologics Division");
        employee.setStatus("SYNCED");
        LocalDate date = LocalDate.of(2026, 8, 20);
        when(conditionRuleService.buildAccessibleAudienceExport(9L, date))
                .thenReturn(new ConditionRuleService.AudienceExportData(rule, date, List.of(employee)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ConditionRuleAudienceExportService(conditionRuleService)
                .export(9L, date, output, Locale.US);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Matched Employees");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee ID");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue())
                    .isEqualTo("Level 1 Organization (Division)");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("WuXi Biologics");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("Biologics Division");
            assertThat(sheet.getRow(1).getCell(columnIndex(sheet, "Status")).getStringCellValue())
                    .isEqualTo("Synced");
        }
    }

    private int columnIndex(org.apache.poi.ss.usermodel.Sheet sheet, String header) {
        for (var cell : sheet.getRow(0)) {
            if (header.equals(cell.getStringCellValue())) return cell.getColumnIndex();
        }
        throw new AssertionError("Missing export header: " + header);
    }

    private SysUser user(String employeeId, String name) {
        SysUser user = new SysUser();
        user.setEmployeeId(employeeId);
        user.setName(name);
        user.setStatus("Active");
        return user;
    }
}
