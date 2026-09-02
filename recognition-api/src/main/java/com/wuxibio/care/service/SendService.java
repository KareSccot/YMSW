package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.channel.EmailChannel;
import com.wuxibio.care.channel.MessageChannel;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.dto.MdLookupItem;
import com.wuxibio.care.dto.SendMailboxOption;
import com.wuxibio.care.entity.FieldRegistry;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskRecipientItem;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TaskTemplateFieldBinding;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SendService {

    private static final Logger log = LoggerFactory.getLogger(SendService.class);
    private static final Set<String> RUNTIME_SYSTEM_TOKENS = Set.of(
            "EmployeeId", "Date"
    );
    private static final String[][] SYSTEM_FIELD_ALIAS_GROUPS = {
            {"EmployeeId", "employeeId"},
            {"Name", "name"},
            {"Email", "email"},
            {"Phone", "phone"},
            {"Department", "department"},
            {"Country", "country"},
            {"CompanyName", "companyName"},
            {"JobTitle", "jobTitle"},
            {"Division", "division"},
            {"ThirdDepartment", "thirdDepartment"},
            {"FourthDepartment", "fourthDepartment"},
            {"FifthDepartment", "fifthDepartment"},
            {"Location", "location"},
            {"SourceType", "sourceType"},
            {"DingTalkUserId", "dingtalkUserId"},
            {"Status", "status"}
    };
    private static final Map<String, String> SYSTEM_FIELD_CANONICAL_BY_ALIAS = buildSystemFieldCanonicalByAlias();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OdataService odataService;
    private final ExternalConnectionService connectionService;
    private final TemplateSenderMailboxService templateSenderMailboxService;
    private final TaskTemplateService taskTemplateService;
    private final TemplateCenterService templateCenterService;
    private final FieldRegistryService fieldRegistryService;
    private final RecipientScopeService recipientScopeService;
    private final ConditionExpressionService conditionExpressionService;
    private final TaskGovernanceService taskGovernanceService;
    private final RunCenterService runCenterService;
    private final IntegrationLogService integrationLogService;
    private final ConditionRuleService conditionRuleService;
    private final MasterDataLookupService masterDataLookupService;
    private final SysUserMapper sysUserMapper;
    private final Map<String, MessageChannel> channelMap;

    public SendService(
            OdataService odataService,
            ExternalConnectionService connectionService,
            TemplateSenderMailboxService templateSenderMailboxService,
            TaskTemplateService taskTemplateService,
            TemplateCenterService templateCenterService,
            FieldRegistryService fieldRegistryService,
            RecipientScopeService recipientScopeService,
            ConditionExpressionService conditionExpressionService,
            TaskGovernanceService taskGovernanceService,
            RunCenterService runCenterService,
            IntegrationLogService integrationLogService,
            ConditionRuleService conditionRuleService,
            MasterDataLookupService masterDataLookupService,
            SysUserMapper sysUserMapper,
            List<MessageChannel> channels) {
        this.odataService = odataService;
        this.connectionService = connectionService;
        this.templateSenderMailboxService = templateSenderMailboxService;
        this.taskTemplateService = taskTemplateService;
        this.templateCenterService = templateCenterService;
        this.fieldRegistryService = fieldRegistryService;
        this.recipientScopeService = recipientScopeService;
        this.conditionExpressionService = conditionExpressionService;
        this.taskGovernanceService = taskGovernanceService;
        this.runCenterService = runCenterService;
        this.integrationLogService = integrationLogService;
        this.conditionRuleService = conditionRuleService;
        this.masterDataLookupService = masterDataLookupService;
        this.sysUserMapper = sysUserMapper;
        this.channelMap = channels.stream().collect(Collectors.toMap(MessageChannel::getType, c -> c));
    }

    private record ResolvedMailboxSelection(
            String source,
            Long senderMailboxId,
            Long externalConnectionId,
            String name,
            String host,
            String port,
            String username,
            String fromAddress,
            String fromName,
            Map<String, String> config,
            Map<String, String> metadata) {
    }

    private record PreparedRecipient(
            String employeeId,
            String recipient,
            String subject,
            String content,
            String channelPayloadJson,
            String messageType,
            String renderSnapshotJson,
            List<String> blockedFields) {
    }

    public SendMailboxOption resolveMailboxOption(Long taskTemplateId, Long templateId) {
        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(taskTemplateId);
        TemplateChannelVariant template = ensureTemplateBelongsToTaskTemplate(taskTemplate, templateId);
        if (!"Email".equals(template.getChannel())) {
            return null;
        }
        TemplateSenderMailboxService.Resolution resolved =
                templateSenderMailboxService.resolveForTemplateHeader(template.getTemplateHeaderId());
        if (resolved == null) {
            throw new BizException("未配置激活的 SMTP 连接，请先在系统连接中激活 SMTP");
        }
        String incompleteMessage = EmailChannel.MAILBOX_SOURCE_SENDER_MAILBOX.equals(resolved.source())
                ? "模板组绑定的发件箱 SMTP 配置不完整"
                : "激活 SMTP 连接配置不完整";
        requireCompleteSmtpConfig(resolved.config(), incompleteMessage);
        return templateSenderMailboxService.toOption(resolved);
    }

    /**
     * Generate Manual upload template driven by Task Template.
     * Columns: EmployeeId + Manual field bindings (respect required/policy).
     */
    public byte[] generateExcelTemplateByTaskTemplate(Long taskTemplateId) {
        return generateExcelTemplateByTaskTemplate(taskTemplateId, null);
    }

    public byte[] generateExcelTemplateByTaskTemplate(Long taskTemplateId, Long templateId) {
        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(taskTemplateId);
        if (!"Manual".equals(taskTemplate.getMode())) {
            throw new BizException("Auto 模式不需要上传模板");
        }

        List<TaskTemplateService.ResolvedBinding> resolvedBindings =
                taskTemplateService.getResolvedBindings(taskTemplateId, templateId);
        List<TaskTemplateService.ResolvedBinding> uploadBindings = resolvedBindings.stream()
                .filter(binding -> Set.of("Manual", "System").contains(binding.field().getSourceType()))
                .toList();

        // Collect custom tokens from the selected template variant (tokens not covered by bindings or system tokens)
        List<CustomTokenColumn> customTokenColumns = resolveCustomTokenColumns(taskTemplateId, templateId, resolvedBindings);

        // Prefill only when the Task Template binds a published Condition Rule version.
        List<SysUser> prefillMembers = List.of();
        boolean hasPrefill = taskTemplate.getConditionRuleVersionId() != null;
        Map<String, MdLookupItem> deptLookup = Map.of();
        Map<String, MdLookupItem> countryLookup = Map.of();
        Map<String, MdLookupItem> companyLookup = Map.of();
        if (hasPrefill) {
            prefillMembers = conditionRuleService.findMatchingEmployees(
                    taskTemplate.getConditionRuleVersionId(),
                    java.time.LocalDate.now());
            Set<String> deptCodes = prefillMembers.stream()
                    .map(SysUser::getDepartment).filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
            Set<String> countryCodes = prefillMembers.stream()
                    .map(SysUser::getCountry).filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
            Set<String> companyCodes = prefillMembers.stream()
                    .map(SysUser::getCompanyName).filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
            deptLookup = masterDataLookupService.batchLookupByCodes(
                    MasterDataLookupService.DIMENSION_DEPARTMENT, deptCodes);
            countryLookup = masterDataLookupService.batchLookupByCodes(
                    MasterDataLookupService.DIMENSION_COUNTRY, countryCodes);
            companyLookup = masterDataLookupService.batchLookupByCodes(
                    MasterDataLookupService.DIMENSION_COMPANY, companyCodes);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("任务上传数据");
            Row headerRow = sheet.createRow(0);
            Row noteRow = sheet.createRow(1);
            DataFormat dataFormat = wb.createDataFormat();
            short textFormat = dataFormat.getFormat("@");

            CellStyle textDataStyle = wb.createCellStyle();
            textDataStyle.setDataFormat(textFormat);

            CellStyle requiredStyle = wb.createCellStyle();
            Font requiredFont = wb.createFont();
            requiredFont.setBold(true);
            requiredFont.setColor(IndexedColors.RED.getIndex());
            requiredStyle.setFont(requiredFont);
            requiredStyle.setDataFormat(textFormat);

            CellStyle optionalStyle = wb.createCellStyle();
            Font optionalFont = wb.createFont();
            optionalFont.setBold(true);
            optionalFont.setColor(IndexedColors.BLUE.getIndex());
            optionalStyle.setFont(optionalFont);
            optionalStyle.setDataFormat(textFormat);

            CellStyle noteStyle = wb.createCellStyle();
            Font noteFont = wb.createFont();
            noteFont.setItalic(true);
            noteFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            noteStyle.setFont(noteFont);
            noteStyle.setDataFormat(textFormat);

            CellStyle refHeaderStyle = wb.createCellStyle();
            Font refHeaderFont = wb.createFont();
            refHeaderFont.setBold(true);
            refHeaderFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            refHeaderStyle.setFont(refHeaderFont);
            refHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            refHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            refHeaderStyle.setDataFormat(textFormat);

            CellStyle refDataStyle = wb.createCellStyle();
            refDataStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            refDataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            refDataStyle.setDataFormat(textFormat);

            int col = 0;
            Cell cell = headerRow.createCell(col);
            cell.setCellValue("EmployeeId");
            cell.setCellStyle(requiredStyle);
            Cell note = noteRow.createCell(col);
            note.setCellValue("必填 - 员工工号");
            note.setCellStyle(noteStyle);
            int employeeIdCol = col;
            col++;

            Set<String> systemInputKeys = uploadBindings.stream()
                    .filter(binding -> "System".equals(binding.field().getSourceType()))
                    .map(binding -> tokenIdentity(binding.field().getCode()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> referenceHeaderKeys = Arrays.stream(REF_COLUMN_HEADERS)
                    .map(this::tokenIdentity)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // Reference columns appear only when prefilling so the un-bound flow keeps its current column layout.
            int[] refCols = new int[REF_COLUMN_HEADERS.length];
            if (hasPrefill) {
                for (int i = 0; i < REF_COLUMN_HEADERS.length; i++) {
                    boolean systemOverrideColumn = systemInputKeys.contains(tokenIdentity(REF_COLUMN_HEADERS[i]));
                    cell = headerRow.createCell(col);
                    cell.setCellValue(REF_COLUMN_HEADERS[i]);
                    cell.setCellStyle(systemOverrideColumn ? optionalStyle : refHeaderStyle);
                    note = noteRow.createCell(col);
                    note.setCellValue(systemOverrideColumn
                            ? "可选 - " + REF_COLUMN_LABELS[i] + "（留空按 EmployeeId 从系统带出，填写值优先）"
                            : "参考(只读) - " + REF_COLUMN_LABELS[i]);
                    note.setCellStyle(noteStyle);
                    refCols[i] = col;
                    col++;
                }
            }

            int manualStartCol = col;
            for (TaskTemplateService.ResolvedBinding resolved : uploadBindings) {
                TaskTemplateFieldBinding binding = resolved.binding();
                FieldRegistry field = resolved.field();
                boolean systemField = "System".equals(field.getSourceType());
                if (systemField && hasPrefill && referenceHeaderKeys.contains(tokenIdentity(field.getCode()))) {
                    continue;
                }
                boolean required = !systemField
                        && binding.getRequiredFlag() != null
                        && binding.getRequiredFlag() == 1;

                cell = headerRow.createCell(col);
                cell.setCellValue(field.getCode());
                cell.setCellStyle(required ? requiredStyle : optionalStyle);

                StringBuilder noteText = new StringBuilder(required ? "必填" : "可选");
                noteText.append(" - ").append(field.getName());
                if (systemField) {
                    noteText.append("（留空按 EmployeeId 从系统带出，填写值优先）");
                } else {
                    if (binding.getMissingPolicy() != null) {
                        noteText.append("（").append(binding.getMissingPolicy()).append("）");
                    }
                    if (binding.getDefaultValue() != null && !binding.getDefaultValue().isBlank()) {
                        noteText.append(" 默认: ").append(binding.getDefaultValue());
                    }
                }
                note = noteRow.createCell(col);
                note.setCellValue(noteText.toString());
                note.setCellStyle(noteStyle);
                col++;
            }

            for (CustomTokenColumn tokenCol : customTokenColumns) {
                cell = headerRow.createCell(col);
                cell.setCellValue(tokenCol.key());
                cell.setCellStyle(optionalStyle);
                note = noteRow.createCell(col);
                note.setCellValue("可选 - " + tokenCol.label());
                note.setCellStyle(noteStyle);
                col++;
            }

            // Prefill data rows (from row 2 onwards).
            if (hasPrefill && !prefillMembers.isEmpty()) {
                int rowIdx = 2;
                for (SysUser user : prefillMembers) {
                    Row dataRow = sheet.createRow(rowIdx++);
                    Cell idCell = dataRow.createCell(employeeIdCol);
                    idCell.setCellValue(safe(user.getEmployeeId()));
                    idCell.setCellStyle(textDataStyle);

                    String[] refValues = new String[] {
                            safe(user.getName()),
                            safe(user.getEmail()),
                            translateOrFallback(user.getDepartment(), deptLookup),
                            translateOrFallback(user.getCountry(), countryLookup),
                            translateOrFallback(user.getCompanyName(), companyLookup),
                    };
                    for (int i = 0; i < refCols.length; i++) {
                        Cell refCell = dataRow.createCell(refCols[i]);
                        refCell.setCellValue(refValues[i]);
                        refCell.setCellStyle(systemInputKeys.contains(tokenIdentity(REF_COLUMN_HEADERS[i]))
                                ? textDataStyle
                                : refDataStyle);
                    }
                    // Manual + custom token columns left blank for the user to fill.
                    for (int c = manualStartCol; c < col; c++) {
                        Cell blankCell = dataRow.createCell(c);
                        blankCell.setCellValue("");
                        blankCell.setCellStyle(textDataStyle);
                    }
                }
            }

            for (int i = 0; i < col; i++) {
                sheet.setDefaultColumnStyle(i, textDataStyle);
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("生成 Task Template 上传模板失败: " + e.getMessage());
        }
    }

    private static final String[] REF_COLUMN_HEADERS = {"Name", "Email", "Department", "Country", "CompanyName"};
    private static final String[] REF_COLUMN_LABELS = {"姓名", "邮箱", "部门", "国家", "法人公司"};

    private static String safe(String s) { return s == null ? "" : s; }

    private static String translateOrFallback(String code, Map<String, MdLookupItem> lookup) {
        if (code == null || code.isBlank()) return "";
        MdLookupItem item = lookupItem(code, lookup);
        if (item == null) return code;
        return item.getLabelZh() != null ? item.getLabelZh()
                : (item.getLabelEn() != null ? item.getLabelEn() : code);
    }

    private static MdLookupItem lookupItem(String code, Map<String, MdLookupItem> lookup) {
        if (code == null || code.isBlank() || lookup == null || lookup.isEmpty()) {
            return null;
        }
        String normalized = code.trim();
        MdLookupItem exact = lookup.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, MdLookupItem> entry : lookup.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Parse Manual upload data driven by Task Template field bindings.
     */
    public Map<String, Object> parseExcelByTaskTemplate(Long taskTemplateId, MultipartFile file) {
        return parseExcelByTaskTemplate(taskTemplateId, null, file);
    }

    public Map<String, Object> parseExcelByTaskTemplate(Long taskTemplateId, Long templateId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请上传文件");
        }

        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(taskTemplateId);
        if (!"Manual".equals(taskTemplate.getMode())) {
            throw new BizException("Auto 模式不需要上传 Manual 文件");
        }

        List<TaskTemplateService.ResolvedBinding> manualBindings = taskTemplateService.getResolvedBindings(taskTemplateId, templateId).stream()
                .filter(binding -> "Manual".equals(binding.field().getSourceType()))
                .toList();
        Map<String, TaskTemplateService.ResolvedBinding> bindingByCode = new LinkedHashMap<>();
        for (TaskTemplateService.ResolvedBinding binding : manualBindings) {
            bindingByCode.put(binding.field().getCode(), binding);
        }

        try (InputStream is = file.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
            DataFormatter dataFormatter = new DataFormatter(Locale.US);
            Sheet sheet = wb.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BizException("Excel 为空");
            }

            List<String> headers = new ArrayList<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                headers.add(cell != null ? getCellString(cell, dataFormatter).trim() : "");
            }

            if (!headers.contains("EmployeeId")) {
                throw new BizException("Excel 缺少 EmployeeId 列");
            }
            for (String fieldCode : bindingByCode.keySet()) {
                if (!headers.contains(fieldCode)) {
                    throw new BizException("Excel 缺少字段列: " + fieldCode);
                }
            }

            List<Map<String, String>> rawRows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> rowData = new LinkedHashMap<>();
                boolean hasData = false;
                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c);
                    if (header == null || header.isBlank()) {
                        continue;
                    }
                    String val = getCellString(row.getCell(c), dataFormatter);
                    if (isTemplateInstructionCell(r, val)) {
                        val = "";
                    }
                    rowData.put(header, val);
                    if (!val.isBlank()) {
                        hasData = true;
                    }
                }
                if (!hasData) {
                    continue;
                }
                rawRows.add(rowData);
            }
            if (rawRows.isEmpty()) {
                throw new BizException("Excel 中没有数据行");
            }

            List<String> employeeIds = rawRows.stream()
                    .map(r -> r.getOrDefault("EmployeeId", "").trim())
                    .filter(id -> !id.isBlank())
                    .distinct()
                    .toList();

            Map<String, Map<String, String>> sfData = Map.of();
            boolean odataFetchFailed = false;
            try {
                sfData = odataService.fetchEmployeesByIds(employeeIds);
                log.info("[SEND-TASK] Fetched {} employees from SF OData", sfData.size());
            } catch (Exception e) {
                log.error("[SEND-TASK] OData fetch failed: {}", e.getMessage(), e);
                odataFetchFailed = true;
            }

            ReferenceLookups referenceLookups = loadReferenceLookups(combineLookupSources(rawRows, sfData.values()));

            RecipientScopeService.ScopeValidationResult scopeValidation =
                    recipientScopeService.validateByEmployeeIds(employeeIds, taskTemplateId);
            Set<String> roleDenied = scopeValidation.roleScopeDeniedEmployeeIds();
            Set<String> taskDenied = scopeValidation.taskScopeDeniedEmployeeIds();

            List<Map<String, String>> validRows = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            for (int idx = 0; idx < rawRows.size(); idx++) {
                Map<String, String> row = rawRows.get(idx);
                String empId = row.getOrDefault("EmployeeId", "").trim();
                List<String> rowErrors = new ArrayList<>();

                if (empId.isBlank()) {
                    rowErrors.add("缺少 EmployeeId");
                }
                if (!empId.isBlank() && taskDenied.contains(empId)) {
                    rowErrors.add("员工 " + empId + " 不在本任务绑定的通用条件规则范围内");
                }
                if (!empId.isBlank() && roleDenied.contains(empId)) {
                    rowErrors.add("员工 " + empId + " 不在当前账号授权范围内");
                }

                Map<String, String> sfEmpData = sfData.getOrDefault(empId, Map.of());
                if (!empId.isBlank() && !odataFetchFailed && sfEmpData.isEmpty()) {
                    rowErrors.add("SF系统中未找到该员工(工号: " + empId + ")");
                }

                Map<String, String> enrichedRow = new LinkedHashMap<>(row);
                for (TaskTemplateService.ResolvedBinding resolved : manualBindings) {
                    TaskTemplateFieldBinding binding = resolved.binding();
                    FieldRegistry field = resolved.field();
                    String code = field.getCode();
                    String value = safeTrim(row.getOrDefault(code, ""));

                    String policy = binding.getMissingPolicy() == null ? "BLOCK" : binding.getMissingPolicy();
                    policy = policy.toUpperCase(Locale.ROOT);
                    if (value.isBlank() && "DEFAULT".equals(policy)
                            && binding.getDefaultValue() != null
                            && !binding.getDefaultValue().isBlank()) {
                        value = binding.getDefaultValue().trim();
                    }

                    boolean required = binding.getRequiredFlag() != null && binding.getRequiredFlag() == 1;
                    if (required && value.isBlank()) {
                        rowErrors.add("字段缺失: " + code + " (必填)");
                    } else if (!required && "BLOCK".equals(policy) && value.isBlank()) {
                        rowErrors.add("字段缺失: " + code + " (策略 BLOCK)");
                    }
                    enrichedRow.put(code, value);
                }

                enrichedRow = applySystemFieldFallbacks(enrichedRow, sfEmpData);
                enrichedRow = applySourceBindingDefinitions(taskTemplateId, templateId, enrichedRow, sfEmpData);
                enrichedRow = applySystemFieldFallbacks(enrichedRow, sfEmpData);
                enrichedRow = applyDingTalkUserIdFromSystem(enrichedRow);
                enrichedRow = translateSystemReferenceFields(enrichedRow, referenceLookups);

                if (!rowErrors.isEmpty()) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("row", idx + 2);
                    err.put("data", enrichedRow);
                    err.put("errors", rowErrors);
                    errors.add(err);
                } else {
                    validRows.add(enrichedRow);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskTemplateId", taskTemplateId);
            result.put("templateId", templateId);
            result.put("taskTemplateName", taskTemplate.getName());
            result.put("totalRows", rawRows.size());
            result.put("validRows", validRows.size());
            result.put("errorCount", errors.size());
            result.put("invalidRows", errors.size());
            result.put("suspendedRows", 0);
            result.put("failedRows", 0);
            result.put("sentRows", 0);
            result.put("rows", validRows);
            result.put("errors", errors);
            result.put("mode", taskTemplate.getMode());
            result.put("scopeSnapshot", scopeValidation.scopeSnapshotJson());

            List<String> warnings = new ArrayList<>();
            if (odataFetchFailed) {
                warnings.add("SF系统连接失败，员工姓名/邮箱等系统字段未自动补全");
            }
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析 Task Template Excel 失败: " + e.getMessage());
        }
    }

    public Map<String, Object> previewTaskTemplateRow(Long taskTemplateId, Long templateId, Map<String, String> rowData) {
        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(taskTemplateId);
        TemplateChannelVariant tpl = ensureTemplateBelongsToTaskTemplate(taskTemplate, templateId);

        String employeeId = safeTrim(rowData.getOrDefault("EmployeeId", ""));
        if (employeeId.isEmpty()) {
            throw new BizException("预览数据缺少 EmployeeId");
        }
        RecipientScopeService.ScopeValidationResult scopeValidation =
                recipientScopeService.validateByEmployeeIds(List.of(employeeId));
        if (!scopeValidation.deniedEmployeeIds().isEmpty()) {
            throw new BizException("员工 " + employeeId + " 不在当前账号授权范围内，禁止预览");
        }

        Map<String, String> sfData = odataService.fetchEmployeesByIds(List.of(employeeId)).getOrDefault(employeeId, Map.of());
        ReferenceLookups referenceLookups = loadReferenceLookups(List.of(rowData, sfData));
        Map<String, String> resolvedRow = applySourceBindingDefinitions(taskTemplateId, templateId, rowData, sfData);
        resolvedRow = applySystemFieldFallbacks(resolvedRow, sfData);
        resolvedRow = applyDingTalkUserIdFromSystem(resolvedRow);
        resolvedRow = translateSystemReferenceFields(resolvedRow, referenceLookups);
        Map<String, String> tokenValues = buildTokenValues(resolvedRow);
        Map<String, Object> result = templateCenterService.previewVariantForSend(taskTemplate.getName(), tpl, tokenValues);
        if (result == null || result.isEmpty()) {
            result = new LinkedHashMap<>();
            result.put("subject", replaceTokens(tpl.getSubject(), tokenValues));
            result.put("content", templateCenterService.renderVariantContentForSend(tpl, tokenValues));
            result.put("channel", tpl.getChannel());
            result.put("messageType", templateCenterService.resolveVariantMessageType(tpl));
        } else {
            result = new LinkedHashMap<>(result);
        }
        result.put("recipient", resolveRecipientByChannel(tpl.getChannel(), resolvedRow));
        result.put("employeeName", lookupValue(resolvedRow, "Name"));
        return result;
    }

    public SendSummary confirmTaskTemplateSend(
            Long taskTemplateId,
            Long templateId,
            List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BizException("发送数据不能为空");
        }

        TaskTemplate taskTemplate = taskTemplateService.getExecutableTemplate(taskTemplateId);
        TemplateChannelVariant tpl = ensureTemplateBelongsToTaskTemplate(taskTemplate, templateId);
        if (!"Published".equals(tpl.getStatus())) {
            throw new BizException("只能使用已发布的模板发送，当前状态: " + tpl.getStatus());
        }
        ResolvedMailboxSelection resolvedMailbox = resolveMailboxSelectionForSend(tpl);

        for (int i = 0; i < rows.size(); i++) {
            String empId = safeTrim(rows.get(i).getOrDefault("EmployeeId", ""));
            if (empId.isEmpty()) {
                throw new BizException("第 " + (i + 1) + " 行缺少 EmployeeId");
            }
        }

        RecipientScopeService.ScopeValidationResult scopeValidation =
                recipientScopeService.validateByEmployeeIds(collectEmployeeIds(rows), taskTemplateId);
        if (!scopeValidation.deniedEmployeeIds().isEmpty()) {
            throw new BizException(buildScopeViolationMessage(scopeValidation.deniedEmployeeIds()));
        }

        if ("Email".equals(tpl.getChannel())) {
            List<String> missingEmailEmployeeIds = rows.stream()
                    .filter(row -> lookupValue(row, "Email").isBlank())
                    .map(row -> safeTrim(row.getOrDefault("EmployeeId", "")))
                    .filter(id -> !id.isBlank())
                    .distinct()
                    .toList();
            Map<String, Map<String, String>> fetchedEmployeeData = missingEmailEmployeeIds.isEmpty()
                    ? Map.of()
                    : odataService.fetchEmployeesByIds(missingEmailEmployeeIds);
            Map<String, Map<String, String>> employeeDataById =
                    fetchedEmployeeData == null ? Map.of() : fetchedEmployeeData;
            for (int i = 0; i < rows.size(); i++) {
                String employeeId = safeTrim(rows.get(i).getOrDefault("EmployeeId", ""));
                String email = lookupValue(rows.get(i), "Email");
                if (email.isBlank()) {
                    Map<String, String> rowWithSystemFields = applySystemFieldFallbacks(
                            rows.get(i),
                            employeeDataById.getOrDefault(employeeId, Map.of()));
                    email = lookupValue(rowWithSystemFields, "Email");
                }
                if (isBlockedEmail(email, resolvedMailbox == null ? null : resolvedMailbox.config())) {
                    throw new BizException("第 " + (i + 1) + " 行邮箱 " + email + " 属于受保护域名，禁止发送");
                }
            }
        }

        return executeTaskTemplateSend(taskTemplate, tpl, rows, scopeValidation.scopeSnapshotJson(), resolvedMailbox);
    }

    public SendSummary executeAutoTriggerSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            String operatorUsername) {
        AutoSendPreparation preparation = prepareAutoTriggerSend(taskTemplate, tpl, rows);
        return executeTaskTemplateSend(
                taskTemplate,
                tpl,
                rows,
                scopeSnapshotJson,
                operatorUsername,
                "Auto",
                preparation.mailboxSelection());
    }

    public SendSummary executeAutoTriggerSend(
            TaskRun existingTaskRun,
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            Long approvalRequesterUserId) {
        if (existingTaskRun == null || existingTaskRun.getId() == null) {
            throw new BizException("预创建的 Task Run 不存在");
        }
        if (!taskTemplate.getId().equals(existingTaskRun.getTaskTemplateId())
                || !tpl.getId().equals(existingTaskRun.getChannelVariantId())) {
            throw new BizException("预创建 Task Run 与锁定的 Task/渠道模板不一致");
        }
        AutoSendPreparation preparation = prepareAutoTriggerSend(taskTemplate, tpl, rows);
        String channelSnapshot = buildChannelSelectionSnapshot(tpl, preparation.mailboxSelection());
        runCenterService.updateSystemRunContext(
                existingTaskRun.getId(), rows.size(), scopeSnapshotJson, channelSnapshot);
        return executeTaskTemplateSend(
                taskTemplate,
                tpl,
                rows,
                scopeSnapshotJson,
                existingTaskRun.getStartedBy(),
                "Auto",
                preparation.mailboxSelection(),
                existingTaskRun,
                approvalRequesterUserId,
                true);
    }

    private AutoSendPreparation prepareAutoTriggerSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BizException("自动触发范围无命中人员");
        }
        if (taskTemplate == null || taskTemplate.getId() == null) {
            throw new BizException("Task Template 不存在");
        }
        if (!"Auto".equals(taskTemplate.getMode())) {
            throw new BizException("Auto Trigger 仅支持 Auto 模式 Task Template");
        }
        if (tpl == null || tpl.getId() == null) {
            throw new BizException("未找到可用的渠道模板");
        }
        if (!"Published".equals(tpl.getStatus())) {
            throw new BizException("只能使用已发布的模板发送，当前状态: " + tpl.getStatus());
        }
        for (int i = 0; i < rows.size(); i++) {
            String employeeId = safeTrim(rows.get(i).getOrDefault("EmployeeId", ""));
            if (employeeId.isBlank()) {
                throw new BizException("第 " + (i + 1) + " 个自动触发接收人缺少 EmployeeId");
            }
        }
        if (taskTemplate.getConditionRuleVersionId() != null) {
            ConditionRuleService.EmployeeMatchResult match = conditionRuleService.matchEmployeeIds(
                    taskTemplate.getConditionRuleVersionId(),
                    collectEmployeeIds(rows),
                    java.time.LocalDate.now());
            if (!match.deniedEmployeeIds().isEmpty()) {
                throw new BizException(buildScopeViolationMessage(match.deniedEmployeeIds()));
            }
        }
        ResolvedMailboxSelection resolvedMailbox = resolveMailboxSelectionForSend(tpl);
        if ("Email".equals(tpl.getChannel())) {
            for (int i = 0; i < rows.size(); i++) {
                String email = safeTrim(rows.get(i).getOrDefault("Email", ""));
                if (isBlockedEmail(email, resolvedMailbox == null ? null : resolvedMailbox.config())) {
                    throw new BizException("第 " + (i + 1) + " 个自动触发接收人邮箱 " + email + " 属于受保护域名，禁止发送");
                }
            }
        }
        return new AutoSendPreparation(resolvedMailbox);
    }

    private SendSummary executeTaskTemplateSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson) {
        return executeTaskTemplateSend(taskTemplate, tpl, rows, scopeSnapshotJson, null);
    }

    private SendSummary executeTaskTemplateSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            ResolvedMailboxSelection mailboxSelection) {
        return executeTaskTemplateSend(taskTemplate, tpl, rows, scopeSnapshotJson, null, "Manual", mailboxSelection);
    }

    private SendSummary executeTaskTemplateSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            String operatorUsername,
            String runMode) {
        return executeTaskTemplateSend(taskTemplate, tpl, rows, scopeSnapshotJson, operatorUsername, runMode, null);
    }

    private SendSummary executeTaskTemplateSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            String operatorUsername,
            String runMode,
            ResolvedMailboxSelection mailboxSelection) {
        return executeTaskTemplateSend(
                taskTemplate,
                tpl,
                rows,
                scopeSnapshotJson,
                operatorUsername,
                runMode,
                mailboxSelection,
                null,
                null,
                false);
    }

    private SendSummary executeTaskTemplateSend(
            TaskTemplate taskTemplate,
            TemplateChannelVariant tpl,
            List<Map<String, String>> rows,
            String scopeSnapshotJson,
            String operatorUsername,
            String runMode,
            ResolvedMailboxSelection mailboxSelection,
            TaskRun existingTaskRun,
            Long approvalRequesterUserId,
            boolean systemExecution) {
        MessageChannel channel = channelMap.get(tpl.getChannel());
        if (channel == null) {
            throw new BizException("不支持的渠道: " + tpl.getChannel());
        }

        TaskRun taskRun = existingTaskRun == null
                ? runCenterService.startRun(
                        taskTemplate.getId(),
                        tpl.getId(),
                        rows.size(),
                        scopeSnapshotJson,
                        buildChannelSelectionSnapshot(tpl, mailboxSelection),
                        operatorUsername,
                        runMode)
                : existingTaskRun;

        // 审批治理只沿 Task Template -> Template Header -> Tag -> Workflow 解析。
        TaskGovernanceService.ApprovalGateResult approvalGate;
        try {
            approvalGate = taskGovernanceService.checkSendApprovalGate(taskRun.getId());
        } catch (BizException e) {
            runCenterService.markRunConfigurationFailed(taskRun.getId(), e.getMessage());
            throw e;
        }

        List<PreparedRecipient> preparedRecipients;
        try {
            preparedRecipients = prepareRecipients(taskTemplate, tpl, rows);
        } catch (RuntimeException e) {
            runCenterService.markRunConfigurationFailed(taskRun.getId(), e.getMessage());
            throw e;
        }
        if (approvalGate.blocked()) {
            createApprovalRecipientSnapshots(taskRun, preparedRecipients);
            List<Long> submittedApprovalIds;
            try {
                submittedApprovalIds = (systemExecution
                        ? taskGovernanceService.submitApprovals(taskRun.getId(), approvalRequesterUserId)
                        : taskGovernanceService.submitApprovals(taskRun.getId())).stream()
                        .map(com.wuxibio.care.entity.TaskApprovalInstance::getId)
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } catch (BizException be) {
                runCenterService.markRunConfigurationFailed(taskRun.getId(), be.getMessage());
                throw be;
            }
            runCenterService.markRunPendingApproval(taskRun.getId());
            return buildPendingApprovalSendSummary(taskRun, tpl, rows.size(), submittedApprovalIds, approvalGate.reason());
        }

        int successCount = 0;
        int failCount = 0;
        int suspendedCount = 0;

        for (PreparedRecipient prepared : preparedRecipients) {
            TaskRecipientItem recipientItem = runCenterService.createRecipientItem(
                    taskRun.getId(),
                    prepared.employeeId(),
                    prepared.recipient(),
                    prepared.renderSnapshotJson());

            if (!prepared.blockedFields().isEmpty()) {
                String reason = "字段缺失触发 BLOCK: " + String.join(", ", prepared.blockedFields());
                runCenterService.markSuspendedDataIssue(recipientItem, reason);
                suspendedCount++;
                continue;
            }

            if (prepared.recipient() == null || prepared.recipient().isBlank()) {
                runCenterService.markSuspendedDataIssue(recipientItem, "收件人为空");
                suspendedCount++;
                continue;
            }

            try {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("taskTemplateId", String.valueOf(taskTemplate.getId()));
                if (mailboxSelection != null) {
                    metadata.putAll(mailboxSelection.metadata());
                }
                channel.send(new MessageChannel.MessageRequest(
                        prepared.recipient(),
                        prepared.subject(),
                        prepared.content(),
                        prepared.messageType(),
                        prepared.channelPayloadJson(),
                        metadata));
                runCenterService.markSentSuccess(recipientItem);
                successCount++;
                integrationLogService.log(
                        tpl.getChannel(),
                        prepared.recipient(),
                        truncateLogText(prepared.subject(), 512),
                        "SEND_SUCCESS",
                        "Success",
                        null);
            } catch (Exception e) {
                runCenterService.markSentFailed(recipientItem, e.getMessage());
                failCount++;
                integrationLogService.log(
                        tpl.getChannel(),
                        prepared.recipient(),
                        truncateLogText(prepared.subject(), 512),
                        null,
                        "Failed",
                        truncateLogText(e.getMessage(), 1024));
            }
        }

        if (systemExecution) {
            runCenterService.finishSystemRun(taskRun.getId());
        } else {
            runCenterService.finishRun(taskRun.getId());
        }
        try {
            taskGovernanceService.consumeApprovalsByTaskRun(taskRun.getId());
        } catch (Exception e) {
            log.error("[SEND-TASK] consume approvals failed taskRunId={} cause={}", taskRun.getId(), e.getMessage(), e);
        }
        return buildTaskRunSendSummary(taskRun, tpl, rows.size(), successCount, failCount, suspendedCount);
    }

    private List<PreparedRecipient> prepareRecipients(
            TaskTemplate taskTemplate,
            TemplateChannelVariant template,
            List<Map<String, String>> rows) {
        Map<String, MissingRule> missingRules = buildMissingRules(taskTemplate.getId(), template.getId());
        Map<String, Map<String, String>> sfDataByEmployeeId = odataService.fetchEmployeesByIds(collectEmployeeIds(rows));
        ReferenceLookups referenceLookups = loadReferenceLookups(combineLookupSources(rows, sfDataByEmployeeId.values()));
        List<PreparedRecipient> prepared = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String employeeId = safeTrim(row.getOrDefault("EmployeeId", ""));
            Map<String, String> resolvedRow = applySourceBindingDefinitions(
                    taskTemplate.getId(),
                    template.getId(),
                    row,
                    sfDataByEmployeeId.getOrDefault(employeeId, Map.of()));
            resolvedRow = applySystemFieldFallbacks(
                    resolvedRow,
                    sfDataByEmployeeId.getOrDefault(employeeId, Map.of()));
            resolvedRow = applyDingTalkUserIdFromSystem(resolvedRow);
            MissingPolicyResult policyResult = applyMissingPolicies(resolvedRow, missingRules);
            Map<String, String> processedRow = translateSystemReferenceFields(policyResult.row(), referenceLookups);
            Map<String, String> tokenValues = buildTokenValues(processedRow);
            String subject = replaceTokens(template.getSubject(), tokenValues);
            String content = templateCenterService.renderVariantContentForSend(template, tokenValues);
            String channelPayload = templateCenterService.renderVariantChannelPayloadForSend(template, tokenValues);
            String messageType = templateCenterService.resolveVariantMessageType(template);
            String recipient = resolveRecipientByChannel(template.getChannel(), processedRow);
            Map<String, String> snapshot = buildRenderSnapshot(
                    processedRow,
                    subject,
                    content,
                    channelPayload,
                    messageType);
            prepared.add(new PreparedRecipient(
                    employeeId,
                    recipient,
                    subject,
                    content,
                    channelPayload,
                    messageType,
                    toJsonString(snapshot),
                    List.copyOf(policyResult.blockedFields())));
        }
        return prepared;
    }

    private void createApprovalRecipientSnapshots(
            TaskRun taskRun,
            List<PreparedRecipient> preparedRecipients) {
        for (PreparedRecipient prepared : preparedRecipients) {
            runCenterService.createPendingApprovalRecipientItem(
                    taskRun.getId(),
                    prepared.employeeId(),
                    prepared.recipient(),
                    prepared.renderSnapshotJson());
        }
    }

    private Map<String, String> buildTokenValues(Map<String, String> rowData) {
        Map<String, String> tokenValues = new LinkedHashMap<>();
        Map<String, String> compacted = compactSystemFieldAliases(rowData);
        for (Map.Entry<String, String> entry : compacted.entrySet()) {
            tokenValues.put(entry.getKey(), safeTrim(entry.getValue()));
        }
        applySystemTokenAliases(tokenValues);
        tokenValues.putIfAbsent("Date", LocalDate.now().toString());
        return tokenValues;
    }

    private TemplateChannelVariant ensureTemplateBelongsToTaskTemplate(TaskTemplate taskTemplate, Long templateId) {
        TemplateChannelVariant target = taskTemplateService.listVariantsForTaskTemplate(taskTemplate.getId()).stream()
                .filter(item -> item.getId().equals(templateId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw new BizException("模板不属于当前 Task Template 绑定的模板组");
        }
        return target;
    }

    private String resolveRecipientByChannel(String channel, Map<String, String> row) {
        if ("Email".equals(channel)) {
            return lookupValue(row, "Email");
        }
        if ("DingTalk".equals(channel)) {
            return resolveDingTalkUserIdByEmployeeId(row.getOrDefault("EmployeeId", ""));
        }
        return "";
    }

    private Map<String, String> applyDingTalkUserIdFromSystem(Map<String, String> row) {
        Map<String, String> resolved = compactSystemFieldAliases(row);
        resolved.put("DingTalkUserId", resolveDingTalkUserIdByEmployeeId(resolved.getOrDefault("EmployeeId", "")));
        return resolved;
    }

    private Map<String, String> applySystemFieldFallbacks(Map<String, String> row, Map<String, String> sourceData) {
        Map<String, String> resolved = compactSystemFieldAliases(row);
        for (String[] aliases : SYSTEM_FIELD_ALIAS_GROUPS) {
            if (aliases.length == 0) {
                continue;
            }
            String canonicalKey = aliases[0];
            String currentValue = lookupValue(resolved, canonicalKey);
            String sourceValue = lookupValue(sourceData, canonicalKey);
            String value = !currentValue.isBlank() ? currentValue : sourceValue;
            if (!value.isBlank()) {
                putIfBlank(resolved, canonicalKey, value);
            }
        }
        return resolved;
    }

    private record ReferenceLookups(
            Map<String, MdLookupItem> departments,
            Map<String, MdLookupItem> countries,
            Map<String, MdLookupItem> companies) {
        private static ReferenceLookups empty() {
            return new ReferenceLookups(Map.of(), Map.of(), Map.of());
        }
    }

    private List<Map<String, String>> combineLookupSources(
            Collection<Map<String, String>> primary,
            Collection<Map<String, String>> secondary) {
        List<Map<String, String>> combined = new ArrayList<>();
        if (primary != null) {
            combined.addAll(primary);
        }
        if (secondary != null) {
            combined.addAll(secondary);
        }
        return combined;
    }

    private ReferenceLookups loadReferenceLookups(Collection<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return ReferenceLookups.empty();
        }
        return new ReferenceLookups(
                lookupReferenceDimension(MasterDataLookupService.DIMENSION_DEPARTMENT, collectReferenceValues(rows, "Department")),
                lookupReferenceDimension(MasterDataLookupService.DIMENSION_COUNTRY, collectReferenceValues(rows, "Country")),
                lookupReferenceDimension(MasterDataLookupService.DIMENSION_COMPANY, collectReferenceValues(rows, "CompanyName")));
    }

    private Set<String> collectReferenceValues(Collection<Map<String, String>> rows, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            String value = lookupValue(row, key);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, MdLookupItem> lookupReferenceDimension(String dimension, Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, MdLookupItem> lookup = masterDataLookupService.batchLookupByCodes(dimension, codes);
            return lookup == null ? Map.of() : lookup;
        } catch (Exception e) {
            log.warn("[SEND-TASK] master data label lookup failed dimension={} cause={}", dimension, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> translateSystemReferenceFields(Map<String, String> row, ReferenceLookups lookups) {
        if (row == null || row.isEmpty()) {
            return row;
        }
        Map<String, String> resolved = compactSystemFieldAliases(row);
        translateSystemReferenceField(resolved, "Department", lookups == null ? Map.of() : lookups.departments());
        translateSystemReferenceField(resolved, "Country", lookups == null ? Map.of() : lookups.countries());
        translateSystemReferenceField(resolved, "CompanyName", lookups == null ? Map.of() : lookups.companies());
        return resolved;
    }

    private void translateSystemReferenceField(Map<String, String> row, String key, Map<String, MdLookupItem> lookup) {
        String value = lookupValue(row, key);
        if (value.isBlank()) {
            return;
        }
        row.put(key, translateOrFallback(value, lookup == null ? Map.of() : lookup));
    }

    private static Map<String, String> buildSystemFieldCanonicalByAlias() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] aliases : SYSTEM_FIELD_ALIAS_GROUPS) {
            if (aliases.length == 0 || aliases[0] == null || aliases[0].isBlank()) {
                continue;
            }
            String canonical = aliases[0].trim();
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    result.put(alias.trim().toLowerCase(Locale.ROOT), canonical);
                }
            }
        }
        return Map.copyOf(result);
    }

    private String canonicalSystemFieldKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return SYSTEM_FIELD_CANONICAL_BY_ALIAS.getOrDefault(key.trim().toLowerCase(Locale.ROOT), "");
    }

    private Map<String, String> compactSystemFieldAliases(Map<String, String> row) {
        Map<String, String> compacted = new LinkedHashMap<>();
        if (row == null || row.isEmpty()) {
            return compacted;
        }
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String key = safeTrim(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            String value = safeTrim(entry.getValue());
            String canonical = canonicalSystemFieldKey(key);
            if (canonical.isBlank()) {
                compacted.put(key, value);
                continue;
            }
            String existing = safeTrim(compacted.get(canonical));
            if (!compacted.containsKey(canonical) || existing.isBlank()) {
                compacted.put(canonical, value);
            }
        }
        return compacted;
    }

    private void applySystemTokenAliases(Map<String, String> tokenValues) {
        for (String[] aliases : SYSTEM_FIELD_ALIAS_GROUPS) {
            String value = "";
            for (String alias : aliases) {
                value = lookupValue(tokenValues, alias);
                if (!value.isBlank()) {
                    break;
                }
            }
            if (value.isBlank()) {
                continue;
            }
            for (String alias : aliases) {
                putIfBlank(tokenValues, alias, value);
            }
        }
    }

    private void putIfBlank(Map<String, String> values, String key, String value) {
        if (values == null || key == null || key.isBlank()) {
            return;
        }
        if (!safeTrim(values.get(key)).isBlank()) {
            return;
        }
        values.put(key, safeTrim(value));
    }

    private String resolveDingTalkUserIdByEmployeeId(String employeeId) {
        String normalizedEmployeeId = safeTrim(employeeId);
        if (normalizedEmployeeId.isBlank()) {
            return "";
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getEmployeeId, normalizedEmployeeId)
                .eq(SysUser::getDeleted, 0)
                .orderByAsc(SysUser::getId)
                .last("LIMIT 1"));
        return user == null ? "" : safeTrim(user.getDingtalkUserId());
    }

    private Map<String, MissingRule> buildMissingRules(Long taskTemplateId) {
        return buildMissingRules(taskTemplateId, null);
    }

    private Map<String, MissingRule> buildMissingRules(Long taskTemplateId, Long templateId) {
        Map<String, MissingRule> rules = new LinkedHashMap<>();
        for (TaskTemplateService.ResolvedBinding resolved : taskTemplateService.getResolvedBindings(taskTemplateId, templateId)) {
            TaskTemplateFieldBinding binding = resolved.binding();
            FieldRegistry field = resolved.field();
            String rawFieldCode = safeTrim(field.getCode());
            if (rawFieldCode.isBlank()) {
                continue;
            }
            String canonicalFieldCode = canonicalSystemFieldKey(rawFieldCode);
            String fieldCode = canonicalFieldCode.isBlank() ? rawFieldCode : canonicalFieldCode;

            String policy = safeTrim(binding.getMissingPolicy());
            if (policy.isBlank()) {
                policy = safeTrim(field.getMissingPolicy());
            }
            if (policy.isBlank()) {
                policy = "BLOCK";
            }
            policy = policy.toUpperCase(Locale.ROOT);

            String defaultValue = safeTrim(binding.getDefaultValue());
            if (defaultValue.isBlank()) {
                defaultValue = safeTrim(field.getDefaultValue());
            }
            rules.put(fieldCode, new MissingRule(policy, defaultValue));
        }
        return rules;
    }

    private MissingPolicyResult applyMissingPolicies(Map<String, String> row, Map<String, MissingRule> rules) {
        Map<String, String> normalized = compactSystemFieldAliases(row);

        List<String> blockedFields = new ArrayList<>();
        for (Map.Entry<String, MissingRule> entry : rules.entrySet()) {
            String fieldCode = entry.getKey();
            MissingRule rule = entry.getValue();
            String value = lookupValue(normalized, fieldCode);
            if (!value.isBlank()) {
                normalized.put(fieldCode, value);
                continue;
            }
            switch (rule.policy()) {
                case "EMPTY" -> normalized.put(fieldCode, "");
                case "DEFAULT" -> {
                    if (rule.defaultValue() != null && !rule.defaultValue().isBlank()) {
                        normalized.put(fieldCode, rule.defaultValue());
                    } else {
                        blockedFields.add(fieldCode);
                    }
                }
                case "BLOCK" -> blockedFields.add(fieldCode);
                default -> blockedFields.add(fieldCode);
            }
        }
        return new MissingPolicyResult(normalized, blockedFields);
    }

    private Map<String, String> applySourceBindingDefinitions(
            Long taskTemplateId,
            Map<String, String> rowData,
            Map<String, String> sfData) {
        return applySourceBindingDefinitions(taskTemplateId, null, rowData, sfData);
    }

    private Map<String, String> applySourceBindingDefinitions(
            Long taskTemplateId,
            Long templateId,
            Map<String, String> rowData,
            Map<String, String> sfData) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (rowData != null) {
            rowData.forEach((key, value) -> resolved.put(key, safeTrim(value)));
        }
        Map<String, String> sfNormalized = new LinkedHashMap<>();
        if (sfData != null) {
            sfData.forEach((key, value) -> sfNormalized.put(key, safeTrim(value)));
        }

        Map<String, String> context = new LinkedHashMap<>(sfNormalized);
        context.putAll(resolved);

        for (TaskTemplateService.ResolvedBinding binding : taskTemplateService.getResolvedBindings(taskTemplateId, templateId)) {
            FieldRegistry field = binding.field();
            if (field == null || field.getCode() == null || field.getCode().isBlank()) {
                continue;
            }
            String fieldCode = field.getCode();
            String current = safeTrim(resolved.getOrDefault(fieldCode, ""));
            String computed = resolveBySourceBindingDefinition(field, resolved, sfNormalized, context);

            if (current.isBlank() && !computed.isBlank()) {
                resolved.put(fieldCode, computed);
                context.put(fieldCode, computed);
            }
        }
        return resolved;
    }

    private String resolveBySourceBindingDefinition(
            FieldRegistry field,
            Map<String, String> rowData,
            Map<String, String> sfData,
            Map<String, String> context) {
        String definition = field.getSourceBindingDefinition();
        String sourceType = field.getSourceType() == null ? "" : field.getSourceType().trim();
        String fieldCode = field.getCode();

        if (definition == null || definition.isBlank()) {
            if ("Manual".equals(sourceType)) {
                return lookupValue(rowData, fieldCode);
            }
            return lookupValue(sfData, fieldCode);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = objectMapper.readValue(definition, Map.class);
            String type = safeTrim(root.get("type") == null ? "" : String.valueOf(root.get("type"))).toUpperCase(Locale.ROOT);
            return switch (type) {
                case "ROW" -> lookupValue(rowData, safeTrim(root.get("path") == null ? fieldCode : String.valueOf(root.get("path"))));
                case "SF" -> lookupValue(sfData, safeTrim(root.get("path") == null ? fieldCode : String.valueOf(root.get("path"))));
                case "CONSTANT" -> safeTrim(root.get("value") == null ? "" : String.valueOf(root.get("value")));
                case "EXPRESSION" -> {
                    Object expressionRaw = root.get("expression");
                    String expression = expressionRaw == null ? "" : objectMapper.writeValueAsString(expressionRaw);
                    boolean matched = conditionExpressionService.evaluate(expression, context).matched();
                    String trueValue = safeTrim(root.get("trueValue") == null ? "" : String.valueOf(root.get("trueValue")));
                    String falseValue = safeTrim(root.get("falseValue") == null ? "" : String.valueOf(root.get("falseValue")));
                    yield matched ? trueValue : falseValue;
                }
                default -> {
                    if ("Manual".equals(sourceType)) {
                        yield lookupValue(rowData, fieldCode);
                    }
                    yield lookupValue(sfData, fieldCode);
                }
            };
        } catch (Exception ex) {
            if ("Manual".equals(sourceType)) {
                return lookupValue(rowData, fieldCode);
            }
            return lookupValue(sfData, fieldCode);
        }
    }

    private String lookupValue(Map<String, String> data, String key) {
        if (data == null || data.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        String exact = safeTrim(data.getOrDefault(key, ""));
        if (!exact.isBlank()) {
            return exact;
        }
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return safeTrim(entry.getValue());
            }
        }
        return "";
    }

    private String buildChannelSelectionSnapshot(TemplateChannelVariant tpl) {
        return buildChannelSelectionSnapshot(tpl, null);
    }

    private String buildChannelSelectionSnapshot(TemplateChannelVariant tpl, ResolvedMailboxSelection mailboxSelection) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("channel", tpl.getChannel());
        snapshot.put("channelVariantId", tpl.getId());
        snapshot.put("messageType", templateCenterService.resolveVariantMessageType(tpl));
        snapshot.put("templateSubject", tpl.getSubject());
        snapshot.put("templateStatus", tpl.getStatus());
        if (mailboxSelection != null) {
            Map<String, Object> mailbox = new LinkedHashMap<>();
            mailbox.put("source", mailboxSelection.source());
            mailbox.put("senderMailboxId", mailboxSelection.senderMailboxId());
            mailbox.put("externalConnectionId", mailboxSelection.externalConnectionId());
            mailbox.put("name", mailboxSelection.name());
            mailbox.put("host", mailboxSelection.host());
            mailbox.put("port", mailboxSelection.port());
            mailbox.put("username", mailboxSelection.username());
            mailbox.put("fromAddress", mailboxSelection.fromAddress());
            mailbox.put("fromName", mailboxSelection.fromName());
            snapshot.put("mailbox", mailbox);
        }
        return toJsonString(snapshot);
    }

    private ResolvedMailboxSelection resolveMailboxSelectionForSend(TemplateChannelVariant template) {
        if (template == null || !"Email".equals(template.getChannel())) {
            return null;
        }
        TemplateSenderMailboxService.Resolution resolved =
                templateSenderMailboxService.resolveForTemplateHeader(template.getTemplateHeaderId());
        if (resolved == null) {
            throw new BizException("未配置激活的 SMTP 连接，请先在系统连接中激活 SMTP");
        }
        String incompleteMessage = EmailChannel.MAILBOX_SOURCE_SENDER_MAILBOX.equals(resolved.source())
                ? "模板组绑定的发件箱 SMTP 配置不完整"
                : "激活 SMTP 连接配置不完整";
        requireCompleteSmtpConfig(resolved.config(), incompleteMessage);
        return new ResolvedMailboxSelection(
                resolved.source(),
                resolved.senderMailboxId(),
                resolved.externalConnectionId(),
                resolved.name(),
                resolved.host(),
                resolved.port(),
                resolved.username(),
                resolved.fromAddress(),
                resolved.fromName(),
                resolved.config(),
                resolved.metadata());
    }

    private void requireCompleteSmtpConfig(Map<String, String> cfg, String message) {
        if (!isCompleteSmtpConfig(cfg)) {
            throw new BizException(message);
        }
    }

    private boolean isCompleteSmtpConfig(Map<String, String> cfg) {
        return cfg != null
                && !trim(cfg.get("host")).isBlank()
                && !trim(cfg.get("port")).isBlank()
                && !trim(cfg.get("username")).isBlank()
                && !trim(cfg.get("password")).isBlank();
    }

    private Map<String, String> buildRenderSnapshot(
            Map<String, String> tokenValues,
            String subject,
            String content,
            String renderedChannelPayloadJson,
            String messageType) {
        Map<String, String> snapshot = new LinkedHashMap<>(tokenValues);
        snapshot.put(RunCenterService.SNAPSHOT_RENDERED_SUBJECT, subject == null ? "" : subject);
        snapshot.put(RunCenterService.SNAPSHOT_RENDERED_CONTENT, content == null ? "" : content);
        snapshot.put(RunCenterService.SNAPSHOT_RENDERED_CHANNEL_PAYLOAD, renderedChannelPayloadJson == null ? "" : renderedChannelPayloadJson);
        snapshot.put(RunCenterService.SNAPSHOT_MESSAGE_TYPE, messageType == null ? "" : messageType);
        return snapshot;
    }

    private String toJsonString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private SendSummary buildPendingApprovalSendSummary(
            TaskRun taskRun,
            TemplateChannelVariant tpl,
            int total,
            List<Long> approvalIds,
            String reason) {
        return new SendSummary(
                taskRun.getId(),
                tpl.getId(),
                tpl.getChannel(),
                total,
                0,
                0,
                0,
                0,
                0,
                "Pending_Approval",
                approvalIds == null ? List.of() : approvalIds,
                reason);
    }

    private SendSummary buildTaskRunSendSummary(
            TaskRun taskRun,
            TemplateChannelVariant tpl,
            int total,
            int success,
            int fail,
            int suspended) {
        String status;
        if (fail == 0 && suspended == 0) {
            status = "Completed";
        } else if (success == 0 && fail > 0 && suspended == 0) {
            status = "Failed";
        } else {
            status = "Completed_With_Issue";
        }
        return new SendSummary(
                taskRun.getId(),
                tpl.getId(),
                tpl.getChannel(),
                total,
                success,
                success,
                fail,
                suspended,
                fail + suspended,
                status,
                List.of(),
                null);
    }

    private record AutoSendPreparation(ResolvedMailboxSelection mailboxSelection) {
    }

    /** Load email blacklist from SMTP connection config. Empty means no blacklist restriction. */
    private List<String> getEmailBlacklist() {
        return getEmailBlacklist(connectionService.getActiveConfig("SMTP"));
    }

    private List<String> getEmailBlacklist(Map<String, String> smtpCfg) {
        if (smtpCfg != null && smtpCfg.containsKey("emailBlacklist")) {
            String raw = smtpCfg.get("emailBlacklist");
            if (raw != null && !raw.isBlank()) {
                return Arrays.stream(raw.split("[\\n,;]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.startsWith("@") ? s.toLowerCase() : "@" + s.toLowerCase())
                        .toList();
            }
        }
        return List.of();
    }

    /**
     * Load email whitelist from SMTP connection config. Empty means no restriction.
     */
    private List<String> getEmailWhitelist() {
        return getEmailWhitelist(connectionService.getActiveConfig("SMTP"));
    }

    private List<String> getEmailWhitelist(Map<String, String> smtpCfg) {
        if (smtpCfg != null && smtpCfg.containsKey("emailWhitelist")) {
            String raw = smtpCfg.get("emailWhitelist");
            if (raw != null && !raw.isBlank()) {
                return Arrays.stream(raw.split("[\\n,;]+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.startsWith("@") ? s.toLowerCase() : "@" + s.toLowerCase())
                        .toList();
            }
        }
        return List.of();
    }

    private boolean isBlockedEmail(String email) {
        return isBlockedEmail(email, connectionService.getActiveConfig("SMTP"));
    }

    private boolean isBlockedEmail(String email, Map<String, String> smtpCfg) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String lower = email.toLowerCase().trim();

        List<String> blacklist = getEmailBlacklist(smtpCfg);
        if (blacklist.stream().anyMatch(lower::endsWith)) {
            return true;
        }

        List<String> whitelist = getEmailWhitelist(smtpCfg);
        if (!whitelist.isEmpty()) {
            return whitelist.stream().noneMatch(lower::endsWith);
        }
        return false;
    }

    private List<String> collectEmployeeIds(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(row -> safeTrim(row.getOrDefault("EmployeeId", "")))
                .filter(v -> !v.isBlank())
                .distinct()
                .toList();
    }

    private String buildScopeViolationMessage(Set<String> deniedEmployeeIds) {
        List<String> sorted = deniedEmployeeIds.stream().sorted().toList();
        int limit = Math.min(20, sorted.size());
        String sample = String.join(", ", sorted.subList(0, limit));
        if (sorted.size() > limit) {
            sample = sample + " ...";
        }
        return "发送对象超出授权范围，已阻断。本次越权员工工号: [" + sample + "]，共 " + sorted.size() + " 人";
    }

    private String truncateLogText(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String replaceTokens(String text, Map<String, String> tokenValues) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> entry : tokenValues.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!RUNTIME_SYSTEM_TOKENS.contains(key) && key.startsWith("__")) {
                continue;
            }
            result = result.replace("{{" + key + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String value) {
        return safeTrim(value);
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = safeTrim(value);
        return normalized.isBlank() ? safeTrim(fallback) : normalized;
    }

    private String getCellString(Cell cell, DataFormatter dataFormatter) {
        if (cell == null) {
            return "";
        }
        return dataFormatter.formatCellValue(cell).trim();
    }

    private boolean isTemplateInstructionCell(int rowIndex, String value) {
        if (rowIndex != 1) {
            return false;
        }
        String normalized = safeTrim(value);
        return normalized.startsWith("必填 - ")
                || normalized.startsWith("可选 - ")
                || normalized.startsWith("参考(只读) - ");
    }

    private record CustomTokenColumn(String key, String label) {
    }

    private List<CustomTokenColumn> resolveCustomTokenColumns(
            Long taskTemplateId, Long templateId,
            List<TaskTemplateService.ResolvedBinding> resolvedBindings) {
        if (templateId == null) return List.of();
        TemplateChannelVariant variant = taskTemplateService.listVariantsForTaskTemplate(taskTemplateId).stream()
                .filter(v -> v.getId().equals(templateId))
                .findFirst()
                .orElse(null);
        if (variant == null || variant.getTokensJson() == null || variant.getTokensJson().isBlank()) {
            return List.of();
        }
        Set<String> coveredKeys = new java.util.HashSet<>();
        RUNTIME_SYSTEM_TOKENS.forEach(token -> coveredKeys.add(tokenIdentity(token)));
        for (String[] aliases : SYSTEM_FIELD_ALIAS_GROUPS) {
            for (String alias : aliases) {
                coveredKeys.add(tokenIdentity(alias));
            }
        }
        resolvedBindings.forEach(b -> coveredKeys.add(tokenIdentity(b.field().getCode())));
        try {
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(variant.getTokensJson());
            if (!arr.isArray()) return List.of();
            List<CustomTokenColumn> result = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                String key = node.path("key").asText("").trim();
                String label = node.path("label").asText(key).trim();
                if (!key.isEmpty() && coveredKeys.add(tokenIdentity(key))) {
                    result.add(new CustomTokenColumn(key, label.isEmpty() ? key : label));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String tokenIdentity(String key) {
        String canonical = canonicalSystemFieldKey(key);
        return (canonical.isBlank() ? safeTrim(key) : canonical).toLowerCase(Locale.ROOT);
    }

    private record MissingRule(String policy, String defaultValue) {
    }

    private record MissingPolicyResult(Map<String, String> row, List<String> blockedFields) {
    }

    public record SendSummary(
            Long id,
            Long templateId,
            String channel,
            Integer totalCount,
            Integer sentCount,
            Integer successCount,
            Integer failedCount,
            Integer suspendedCount,
            Integer failCount,
            String status,
            List<Long> approvalIds,
            String pendingReason) {
    }
}
