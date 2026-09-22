package com.wuxibio.care.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalAudienceExportServiceTest {

    @Test
    void exportWritesCompleteApprovalRecipientSnapshot() throws Exception {
        TaskGovernanceService taskGovernanceService = mock(TaskGovernanceService.class);
        ApprovalAudienceExportService service = new ApprovalAudienceExportService(taskGovernanceService);
        when(taskGovernanceService.listApprovalRecipientsForExport(88L, 42L, false))
                .thenReturn(List.of(
                        new TaskGovernanceService.ApprovalRecipientExportRow(
                                "E1001", "Alice", "a***@example.com", "Pending_Approval"),
                        new TaskGovernanceService.ApprovalRecipientExportRow(
                                "=E1002", "Bob", "b***@example.com", "Pending_Approval")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ApprovalAudienceExportService.ExportDescriptor descriptor = service.export(
                88L, 42L, false, output, Locale.SIMPLIFIED_CHINESE);

        assertEquals("approval-88-recipients.xlsx", descriptor.filename());
        assertEquals(2, descriptor.rowCount());
        assertTrue(output.size() > 0);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("工号", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("Alice", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("'=E1002", workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue());
        }
        verify(taskGovernanceService).listApprovalRecipientsForExport(88L, 42L, false);
    }
}
