package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.EmployeeAssignment;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.EmployeeAssignmentMapper;
import com.wuxibio.care.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EmployeeAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeAssignmentService.class);

    private final EmployeeAssignmentMapper assignmentMapper;
    private final SysUserMapper sysUserMapper;
    private final MasterDataLabelService labelService;

    public EmployeeAssignmentService(EmployeeAssignmentMapper assignmentMapper,
                                     SysUserMapper sysUserMapper,
                                     MasterDataLabelService labelService) {
        this.assignmentMapper = assignmentMapper;
        this.sysUserMapper = sysUserMapper;
        this.labelService = labelService;
    }

    public List<EmployeeAssignment> listForUser(Long sysUserId) {
        SysUser person = sysUserMapper.selectById(sysUserId);
        if (person == null) throw new BizException("用户不存在");
        List<EmployeeAssignment> rows = assignmentMapper.selectList(
                new LambdaQueryWrapper<EmployeeAssignment>()
                        .eq(EmployeeAssignment::getSysUserId, sysUserId)
                        .eq(EmployeeAssignment::getSourceActive, 1));
        applyDisplayLabels(rows, person);
        return rows.stream().sorted(assignmentOrder()).toList();
    }

    public List<SysUser> selectRuleContexts(String expressionJson, Collection<String> employeeIds) {
        LambdaQueryWrapper<SysUser> userQuery = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .in(SysUser::getStatus, List.of("Active", "ACTIVE", "SYNCED"))
                .isNotNull(SysUser::getEmployeeId)
                .ne(SysUser::getEmployeeId, "")
                .orderByAsc(SysUser::getEmployeeId);
        if (employeeIds != null && !employeeIds.isEmpty()) userQuery.in(SysUser::getEmployeeId, employeeIds);
        List<SysUser> people = sysUserMapper.selectList(userQuery);
        if (people.isEmpty()) return List.of();

        List<Long> userIds = people.stream().map(SysUser::getId).toList();
        List<EmployeeAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<EmployeeAssignment>()
                        .in(EmployeeAssignment::getSysUserId, userIds)
                        .eq(EmployeeAssignment::getSourceActive, 1));
        Map<Long, List<EmployeeAssignment>> byUser = new LinkedHashMap<>();
        for (EmployeeAssignment assignment : assignments) {
            byUser.computeIfAbsent(assignment.getSysUserId(), ignored -> new ArrayList<>()).add(assignment);
        }

        AssignmentSelectionPolicy.Mode mode = AssignmentSelectionPolicy.fromExpression(expressionJson);
        List<SysUser> contexts = new ArrayList<>();
        for (SysUser person : people) {
            List<EmployeeAssignment> personAssignments = byUser.getOrDefault(person.getId(), List.of());
            EmployeeAssignment selected = selectAssignment(personAssignments, mode);
            if (selected != null) contexts.add(toUserContext(person, selected));
            else if (personAssignments.isEmpty()) contexts.add(person);
        }
        return contexts;
    }

    public Map<String, Map<String, String>> tokenValues(
            Collection<String> employeeIds,
            String expressionJson) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (SysUser context : selectRuleContexts(expressionJson, employeeIds)) {
            if (context.getEmployeeId() != null && !context.getEmployeeId().isBlank()) {
                result.put(context.getEmployeeId(), UserMasterFieldCatalog.tokenValues(context));
            }
        }
        return result;
    }

    EmployeeAssignment selectAssignment(
            List<EmployeeAssignment> assignments,
            AssignmentSelectionPolicy.Mode mode) {
        if (assignments == null || assignments.isEmpty()) return null;
        List<EmployeeAssignment> ordered = assignments.stream().sorted(assignmentOrder()).toList();
        List<EmployeeAssignment> eligible = switch (mode) {
            case HOME -> ordered.stream().filter(row -> assignmentClass(row, "ST")).toList();
            case HOST_PRIMARY -> ordered.stream()
                    .filter(row -> assignmentClass(row, "GA") && isPrimary(row)).toList();
            case PRIMARY -> ordered.stream().filter(this::isPrimary).toList();
        };
        if (eligible.size() > 1) {
            log.warn("Multiple {} assignment contexts found for employeeId={}, using sfUserId={}",
                    mode, eligible.get(0).getEmployeeId(), eligible.get(0).getSfUserId());
        }
        if (!eligible.isEmpty()) return eligible.get(0);
        if (mode == AssignmentSelectionPolicy.Mode.PRIMARY) {
            return ordered.stream().filter(row -> assignmentClass(row, "ST")).findFirst().orElse(ordered.get(0));
        }
        return null;
    }

    public SysUser toUserContext(SysUser person, EmployeeAssignment assignment) {
        SysUser context = new SysUser();
        context.setId(person.getId());
        context.setUsername(person.getUsername());
        context.setName(firstNonBlank(assignment.getName(), person.getName()));
        context.setEmail(firstNonBlank(assignment.getEmail(), person.getEmail()));
        context.setPhone(firstNonBlank(assignment.getPhone(), person.getPhone()));
        context.setDepartment(assignment.getDepartment());
        context.setCountry(assignment.getCountry());
        context.setCompanyName(assignment.getCompanyName());
        context.setJobTitle(assignment.getJobTitle());
        context.setPositionCode(assignment.getPositionCode());
        context.setDivision(assignment.getDivision());
        context.setThirdDepartment(assignment.getThirdDepartment());
        context.setFourthDepartment(assignment.getFourthDepartment());
        context.setFifthDepartment(assignment.getFifthDepartment());
        context.setLocation(assignment.getLocation());
        context.setEmployeeType(assignment.getEmployeeType());
        context.setAssignmentClass(assignment.getAssignmentClass());
        context.setManagementJobLevel(assignment.getManagementJobLevel());
        context.setProfessionalJobLevel(assignment.getProfessionalJobLevel());
        context.setJobGrade(assignment.getJobGrade());
        context.setDateOfBirth(assignment.getDateOfBirth());
        context.setHireDate(assignment.getHireDate());
        context.setBenefitsEligibilityStartDate(assignment.getBenefitsEligibilityStartDate());
        context.setContractEndDate(assignment.getContractEndDate());
        context.setProbationEndDate(assignment.getProbationEndDate());
        context.setSourceType(person.getSourceType());
        context.setSyncedAt(assignment.getSyncedAt());
        context.setEmployeeId(person.getEmployeeId());
        context.setDingtalkUserId(person.getDingtalkUserId());
        context.setStatus(person.getStatus());
        context.setDeleted(person.getDeleted());
        context.setCreatedAt(person.getCreatedAt());
        context.setUpdatedAt(person.getUpdatedAt());
        return context;
    }

    private void applyDisplayLabels(List<EmployeeAssignment> assignments, SysUser person) {
        if (assignments == null || assignments.isEmpty()) return;
        List<SysUser> contexts = assignments.stream().map(row -> toUserContext(person, row)).toList();
        labelService.applyUserDisplayLabels(contexts, LocaleContextHolder.getLocale());
        for (int i = 0; i < assignments.size(); i++) copyLabels(contexts.get(i), assignments.get(i));
    }

    private void copyLabels(SysUser source, EmployeeAssignment target) {
        target.setCompanyNameDisplay(source.getCompanyNameDisplay());
        target.setDepartmentDisplay(source.getDepartmentDisplay());
        target.setCountryDisplay(source.getCountryDisplay());
        target.setPositionDisplay(source.getPositionDisplay());
        target.setDivisionDisplay(source.getDivisionDisplay());
        target.setThirdDepartmentDisplay(source.getThirdDepartmentDisplay());
        target.setFourthDepartmentDisplay(source.getFourthDepartmentDisplay());
        target.setFifthDepartmentDisplay(source.getFifthDepartmentDisplay());
        target.setLocationDisplay(source.getLocationDisplay());
        target.setEmployeeTypeDisplay(source.getEmployeeTypeDisplay());
        target.setAssignmentClassDisplay(source.getAssignmentClassDisplay());
        target.setManagementJobLevelDisplay(source.getManagementJobLevelDisplay());
        target.setProfessionalJobLevelDisplay(source.getProfessionalJobLevelDisplay());
        target.setJobGradeDisplay(source.getJobGradeDisplay());
    }

    private Comparator<EmployeeAssignment> assignmentOrder() {
        return Comparator.comparing((EmployeeAssignment row) -> !isPrimary(row))
                .thenComparing(row -> !assignmentClass(row, "ST"))
                .thenComparing(row -> safe(row.getSfUserId()));
    }

    private boolean isPrimary(EmployeeAssignment row) {
        return row != null && Integer.valueOf(1).equals(row.getIsPrimaryAssignment());
    }

    private boolean assignmentClass(EmployeeAssignment row, String expected) {
        return row != null && expected.equalsIgnoreCase(safe(row.getAssignmentClass()));
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
