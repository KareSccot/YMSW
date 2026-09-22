package com.wuxibio.care.service;

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
import java.util.List;
import java.util.Locale;

@Service
public class ApprovalAudienceExportService {

    private final TaskGovernanceService taskGovernanceService;

    public ApprovalAudienceExportService(TaskGovernanceService taskGovernanceService) {
        this.taskGovernanceService = taskGovernanceService;
    }

    public ExportDescriptor export(
            Long approvalId,
            Long currentUserId,
            boolean allowAll,
            OutputStream outputStream,
            Locale locale) throws IOException {
        List<TaskGovernanceService.ApprovalRecipientExportRow> recipients =
                taskGovernanceService.listApprovalRecipientsForExport(approvalId, currentUserId, allowAll);
        boolean english = locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(english ? "Recipients" : "发送人群");
            CellStyle headerStyle = headerStyle(workbook);
            String[] headers = english
                    ? new String[]{"Employee ID", "Name", "Recipient", "Status"}
                    : new String[]{"工号", "姓名", "接收地址", "状态"};
            int[] widths = {16, 20, 32, 20};
            Row header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(headers[index]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, widths[index] * 256);
            }
            int rowIndex = 1;
            for (TaskGovernanceService.ApprovalRecipientExportRow recipient : recipients) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(safeCell(recipient.employeeId()));
                row.createCell(1).setCellValue(safeCell(recipient.employeeName()));
                row.createCell(2).setCellValue(safeCell(recipient.recipient()));
                row.createCell(3).setCellValue(safeCell(recipient.status()));
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, Math.max(0, recipients.size()), 0, headers.length - 1));
            workbook.write(outputStream);
            workbook.dispose();
        }
        return new ExportDescriptor(filename(approvalId), recipients.size());
    }

    public String filename(Long approvalId) {
        return "approval-" + approvalId + "-recipients.xlsx";
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

    private String safeCell(String value) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0) {
            return "'" + normalized;
        }
        return normalized;
    }

    public record ExportDescriptor(String filename, int rowCount) {}
}
