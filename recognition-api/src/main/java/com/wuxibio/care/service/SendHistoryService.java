package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.TaskRecipientItemMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SendHistoryService {

    private final TaskRunMapper taskRunMapper;
    private final TaskRecipientItemMapper taskRecipientItemMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SendHistoryService(
            TaskRunMapper taskRunMapper,
            TaskRecipientItemMapper taskRecipientItemMapper,
            TaskTemplateMapper taskTemplateMapper,
            TemplateChannelVariantMapper templateChannelVariantMapper) {
        this.taskRunMapper = taskRunMapper;
        this.taskRecipientItemMapper = taskRecipientItemMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.templateChannelVariantMapper = templateChannelVariantMapper;
    }

    public Map<String, Object> getStats() {
        List<TaskRun> all = taskRunMapper.selectList(buildRunWrapper());
        int totalSent = 0;
        int totalSuccess = 0;
        int totalFail = 0;
        for (TaskRun run : all) {
            totalSent += safeInt(run.getTotalCount());
            totalSuccess += safeInt(run.getSuccessCount());
            totalFail += safeInt(run.getFailedCount()) + safeInt(run.getSuspendedCount());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("batchCount", all.size());
        stats.put("totalSent", totalSent);
        stats.put("totalSuccess", totalSuccess);
        stats.put("totalFail", totalFail);
        return stats;
    }

    public IPage<Map<String, Object>> listBatches(int page, int size, String keyword) {
        LambdaQueryWrapper<TaskRun> wrapper = buildRunWrapper();
        wrapper.orderByDesc(TaskRun::getStartedAt);

        boolean hasKeyword = keyword != null && !keyword.isBlank();
        List<TaskRun> runs;
        if (hasKeyword) {
            runs = taskRunMapper.selectList(wrapper);
        } else {
            IPage<TaskRun> runPage = taskRunMapper.selectPage(new Page<>(page, size), wrapper);
            List<Map<String, Object>> enriched = enrichRuns(runPage.getRecords());
            Page<Map<String, Object>> result = new Page<>(page, size, runPage.getTotal());
            result.setRecords(enriched);
            return result;
        }

        String kw = keyword.trim().toLowerCase();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : enrichRuns(runs)) {
            String configName = String.valueOf(row.getOrDefault("configName", "")).toLowerCase();
            String templateName = String.valueOf(row.getOrDefault("templateName", "")).toLowerCase();
            String runNo = String.valueOf(row.getOrDefault("runNo", "")).toLowerCase();
            if (configName.contains(kw) || templateName.contains(kw) || runNo.contains(kw)) {
                filtered.add(row);
            }
        }

        int total = filtered.size();
        int fromIdx = Math.min((page - 1) * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        Page<Map<String, Object>> result = new Page<>(page, size, total);
        result.setRecords(filtered.subList(fromIdx, toIdx));
        return result;
    }

    public Map<String, Object> getBatchDetail(Long batchId) {
        TaskRun run = taskRunMapper.selectById(batchId);
        if (run == null) {
            return null;
        }
        checkRunAccess(run);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("batch", toBatchSummary(run));

        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, batchId)
                        .orderByAsc(TaskRecipientItem::getId));
        detail.put("records", toRecordRows(items));
        return detail;
    }

    public byte[] exportRecords(Long batchId) {
        TaskRun run = taskRunMapper.selectById(batchId);
        if (run == null) {
            return new byte[0];
        }
        checkRunAccess(run);

        List<TaskRecipientItem> items = taskRecipientItemMapper.selectList(
                new LambdaQueryWrapper<TaskRecipientItem>()
                        .eq(TaskRecipientItem::getTaskRunId, batchId)
                        .orderByAsc(TaskRecipientItem::getId));

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("发送记录");
            Row header = sheet.createRow(0);
            String[] cols = {"员工编号", "姓名", "收件人", "状态", "错误信息", "发送时间"};
            CellStyle headerStyle = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            for (int i = 0; i < cols.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
            }

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIdx = 1;
            for (Map<String, Object> rowData : toRecordRows(items)) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(String.valueOf(rowData.getOrDefault("employeeId", "")));
                row.createCell(1).setCellValue(String.valueOf(rowData.getOrDefault("employeeName", "")));
                row.createCell(2).setCellValue(String.valueOf(rowData.getOrDefault("recipient", "")));
                row.createCell(3).setCellValue(String.valueOf(rowData.getOrDefault("status", "")));
                row.createCell(4).setCellValue(String.valueOf(rowData.getOrDefault("errorMessage", "")));
                Object sentAt = rowData.get("sentAt");
                if (sentAt instanceof java.time.LocalDateTime time) {
                    row.createCell(5).setCellValue(time.format(dtf));
                } else {
                    row.createCell(5).setCellValue("");
                }
            }

            for (int i = 0; i < cols.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private List<Map<String, Object>> enrichRuns(List<TaskRun> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }

        Set<Long> taskTemplateIds = runs.stream()
                .map(TaskRun::getTaskTemplateId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TaskTemplate> templateById = taskTemplateIds.isEmpty()
                ? Map.of()
                : taskTemplateMapper.selectBatchIds(taskTemplateIds).stream()
                .collect(Collectors.toMap(TaskTemplate::getId, t -> t, (a, b) -> a));

        Set<Long> channelVariantIds = runs.stream()
                .map(TaskRun::getChannelVariantId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, TemplateChannelVariant> variantById = channelVariantIds.isEmpty()
                ? Map.of()
                : templateChannelVariantMapper.selectBatchIds(channelVariantIds).stream()
                .collect(Collectors.toMap(TemplateChannelVariant::getId, t -> t, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskRun run : runs) {
            TaskTemplate taskTemplate = templateById.get(run.getTaskTemplateId());
            TemplateChannelVariant variant = variantById.get(run.getChannelVariantId());

            String channel = variant == null ? "Unknown" : variant.getChannel();
            String templateName = variant == null ? "已删除" : variant.getSubject();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.getId());
            row.put("runNo", run.getRunNo());
            row.put("channel", channel);
            row.put("status", normalizeBatchStatus(run.getStatus()));
            row.put("totalCount", safeInt(run.getTotalCount()));
            row.put("successCount", safeInt(run.getSuccessCount()));
            row.put("failCount", safeInt(run.getFailedCount()) + safeInt(run.getSuspendedCount()));
            row.put("createdAt", run.getStartedAt());
            row.put("configName", taskTemplate == null ? "已删除" : taskTemplate.getName());
            row.put("templateName", templateName);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> toBatchSummary(TaskRun run) {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("id", run.getId());
        batch.put("runNo", run.getRunNo());
        batch.put("channel", resolveRunChannel(run));
        batch.put("status", normalizeBatchStatus(run.getStatus()));
        batch.put("totalCount", safeInt(run.getTotalCount()));
        batch.put("successCount", safeInt(run.getSuccessCount()));
        batch.put("failCount", safeInt(run.getFailedCount()) + safeInt(run.getSuspendedCount()));
        batch.put("createdAt", run.getStartedAt());
        return batch;
    }

    private List<Map<String, Object>> toRecordRows(List<TaskRecipientItem> items) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (TaskRecipientItem item : items) {
            Map<String, String> renderData = parseRenderSnapshot(item.getRenderSnapshotJson());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("employeeId", item.getEmployeeId());
            row.put("employeeName", renderData.getOrDefault("Name", ""));
            row.put("recipient", item.getRecipient());
            row.put("status", normalizeRecordStatus(item.getStatus()));
            row.put("errorMessage", item.getLastErrorMessage());
            row.put("sentAt", item.getUpdatedAt());
            records.add(row);
        }
        return records;
    }

    private String normalizeBatchStatus(String status) {
        if ("Completed_With_Issue".equals(status)) {
            return "Completed";
        }
        return status == null ? "Unknown" : status;
    }

    private String normalizeRecordStatus(String status) {
        return switch (status == null ? "" : status) {
            case "Sent_Success" -> "Success";
            case "Sent_Failed" -> "Failed";
            case "Suspended_Data_Issue" -> "Suspended_Data_Issue";
            case "Sending" -> "Pending";
            default -> status == null ? "Unknown" : status;
        };
    }

    private Map<String, String> parseRenderSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private LambdaQueryWrapper<TaskRun> buildRunWrapper() {
        LambdaQueryWrapper<TaskRun> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtil.isAdmin()) {
            String currentUsername = SecurityUtil.getCurrentUsername();
            if (currentUsername == null || currentUsername.isBlank()) {
                throw new BizException(401, "未登录");
            }
            wrapper.eq(TaskRun::getStartedBy, currentUsername);
        }
        return wrapper;
    }

    private void checkRunAccess(TaskRun run) {
        String currentUsername = SecurityUtil.getCurrentUsername();
        if (!SecurityUtil.isAdmin() && !run.getStartedBy().equals(currentUsername)) {
            throw new BizException("无权访问此记录");
        }
    }

    private String resolveRunChannel(TaskRun run) {
        if (run.getChannelVariantId() == null) {
            return "Unknown";
        }
        TemplateChannelVariant variant = templateChannelVariantMapper.selectById(run.getChannelVariantId());
        return variant == null ? "Unknown" : String.valueOf(variant.getChannel());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
