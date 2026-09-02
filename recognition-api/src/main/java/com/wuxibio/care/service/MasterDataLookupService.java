package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.dto.MdLookupItem;
import com.wuxibio.care.entity.MasterDataCompany;
import com.wuxibio.care.entity.MasterDataCountry;
import com.wuxibio.care.entity.MasterDataDepartment;
import com.wuxibio.care.mapper.MasterDataCompanyMapper;
import com.wuxibio.care.mapper.MasterDataCountryMapper;
import com.wuxibio.care.mapper.MasterDataDepartmentMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only master-data lookup for dropdown + batch translation.
 *
 * <p>Returns active records only (status='Active'). Soft-deleted rows are
 * filtered automatically via {@code @TableLogic}.
 */
@Service
public class MasterDataLookupService {

    public static final String DIMENSION_DEPARTMENT = "department";
    public static final String DIMENSION_COUNTRY = "country";
    public static final String DIMENSION_COMPANY = "company";

    // SuccessFactors OData returns "A" / "I" for status; manual imports may use
    // the full word "Active". Accept either as "active" for lookup.
    private static final java.util.Set<String> STATUS_ACTIVE_VALUES = java.util.Set.of("Active", "A");

    private final MasterDataDepartmentMapper departmentMapper;
    private final MasterDataCountryMapper countryMapper;
    private final MasterDataCompanyMapper companyMapper;

    public MasterDataLookupService(MasterDataDepartmentMapper departmentMapper,
                                   MasterDataCountryMapper countryMapper,
                                   MasterDataCompanyMapper companyMapper) {
        this.departmentMapper = departmentMapper;
        this.countryMapper = countryMapper;
        this.companyMapper = companyMapper;
    }

    public PageResult<MdLookupItem> searchDepartments(String keyword, int page, int size) {
        LambdaQueryWrapper<MasterDataDepartment> w = new LambdaQueryWrapper<>();
        w.in(MasterDataDepartment::getStatus, STATUS_ACTIVE_VALUES);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(MasterDataDepartment::getExternalCode, kw)
                    .or().like(MasterDataDepartment::getNameZhCn, kw)
                    .or().like(MasterDataDepartment::getNameEnUs, kw));
        }
        w.orderByAsc(MasterDataDepartment::getExternalCode);
        IPage<MasterDataDepartment> src = departmentMapper.selectPage(new Page<>(page, size), w);
        return mapPage(src, d -> new MdLookupItem(d.getExternalCode(), d.getNameZhCn(), d.getNameEnUs(), d.getStatus()));
    }

    public PageResult<MdLookupItem> searchCountries(String keyword, int page, int size) {
        LambdaQueryWrapper<MasterDataCountry> w = new LambdaQueryWrapper<>();
        w.in(MasterDataCountry::getStatus, STATUS_ACTIVE_VALUES);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(MasterDataCountry::getExternalCode, kw)
                    .or().like(MasterDataCountry::getLabelZhCn, kw)
                    .or().like(MasterDataCountry::getLabelEnUs, kw)
                    .or().like(MasterDataCountry::getOptionId, kw));
        }
        w.orderByAsc(MasterDataCountry::getExternalCode);
        IPage<MasterDataCountry> src = countryMapper.selectPage(new Page<>(page, size), w);
        return mapPage(src, c -> new MdLookupItem(c.getExternalCode(), c.getLabelZhCn(), c.getLabelEnUs(), c.getStatus()));
    }

    public PageResult<MdLookupItem> searchCompanies(String keyword, int page, int size) {
        LambdaQueryWrapper<MasterDataCompany> w = new LambdaQueryWrapper<>();
        w.in(MasterDataCompany::getStatus, STATUS_ACTIVE_VALUES);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(MasterDataCompany::getExternalCode, kw)
                    .or().like(MasterDataCompany::getNameZhCn, kw)
                    .or().like(MasterDataCompany::getNameEnUs, kw));
        }
        w.orderByAsc(MasterDataCompany::getExternalCode);
        IPage<MasterDataCompany> src = companyMapper.selectPage(new Page<>(page, size), w);
        return mapPage(src, c -> new MdLookupItem(c.getExternalCode(), c.getNameZhCn(), c.getNameEnUs(), c.getStatus()));
    }

    /**
     * Batch translate external codes to lookup items for one dimension.
     * Returns a code → item map. Missing codes are simply absent — caller
     * decides whether to keep the raw code as fallback.
     *
     * <p>Unlike search, this does not filter by status — translation should
     * still succeed for archived/inactive records, since historical task runs
     * may legitimately reference them.
     */
    public Map<String, MdLookupItem> batchLookupByCodes(String dimension, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashSet<String> unique = codes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashSet<String> lookupCodes = expandLookupCodes(unique);
        return switch (dimension) {
            case DIMENSION_DEPARTMENT -> lookupDepartments(lookupCodes);
            case DIMENSION_COUNTRY -> lookupCountries(lookupCodes);
            case DIMENSION_COMPANY -> lookupCompanies(lookupCodes);
            default -> Collections.emptyMap();
        };
    }

    /**
     * Expands selected department codes to the selected departments plus every
     * descendant defined by md_department.parent_external_code. Missing roots
     * remain in the result so a rule still has exact-match behavior before a
     * hierarchy is maintained.
     */
    public List<String> expandDepartmentCodes(Collection<String> rootCodes) {
        LinkedHashSet<String> roots = rootCodes == null ? new LinkedHashSet<>() : rootCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roots.isEmpty()) return List.of();

        List<MasterDataDepartment> departments = departmentMapper.selectList(
                new QueryWrapper<MasterDataDepartment>()
                        .select("external_code", "parent_external_code")
                        .in("status", STATUS_ACTIVE_VALUES));
        Map<String, List<String>> children = new HashMap<>();
        for (MasterDataDepartment department : departments) {
            String code = department.getExternalCode();
            String parent = department.getParentExternalCode();
            if (code == null || code.isBlank() || parent == null || parent.isBlank()) continue;
            children.computeIfAbsent(parent.trim(), ignored -> new ArrayList<>()).add(code.trim());
        }

        LinkedHashSet<String> expanded = new LinkedHashSet<>(roots);
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String child : children.getOrDefault(current, List.of())) {
                if (expanded.add(child)) queue.addLast(child);
            }
        }
        return List.copyOf(expanded);
    }

    private LinkedHashSet<String> expandLookupCodes(Collection<String> codes) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            String trimmed = code.trim();
            expanded.add(trimmed);
            expanded.add(trimmed.toUpperCase(Locale.ROOT));
            expanded.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return expanded;
    }

    private Map<String, MdLookupItem> lookupDepartments(Collection<String> codes) {
        List<MasterDataDepartment> rows = departmentMapper.selectList(new LambdaQueryWrapper<MasterDataDepartment>()
                .in(MasterDataDepartment::getExternalCode, codes));
        Map<String, MdLookupItem> result = new HashMap<>(rows.size());
        for (MasterDataDepartment d : rows) {
            result.put(d.getExternalCode(),
                    new MdLookupItem(d.getExternalCode(), d.getNameZhCn(), d.getNameEnUs(), d.getStatus()));
        }
        return result;
    }

    private Map<String, MdLookupItem> lookupCountries(Collection<String> codes) {
        List<MasterDataCountry> rows = countryMapper.selectList(new LambdaQueryWrapper<MasterDataCountry>()
                .and(q -> q.in(MasterDataCountry::getExternalCode, codes)
                        .or()
                        .in(MasterDataCountry::getOptionId, codes)));
        Map<String, MdLookupItem> result = new HashMap<>(rows.size());
        for (MasterDataCountry c : rows) {
            MdLookupItem item = new MdLookupItem(c.getExternalCode(), c.getLabelZhCn(), c.getLabelEnUs(), c.getStatus());
            putLookupItem(result, c.getExternalCode(), item);
            putLookupItem(result, c.getOptionId(), item);
        }
        return result;
    }

    private Map<String, MdLookupItem> lookupCompanies(Collection<String> codes) {
        List<MasterDataCompany> rows = companyMapper.selectList(new LambdaQueryWrapper<MasterDataCompany>()
                .in(MasterDataCompany::getExternalCode, codes));
        Map<String, MdLookupItem> result = new HashMap<>(rows.size());
        for (MasterDataCompany c : rows) {
            result.put(c.getExternalCode(),
                    new MdLookupItem(c.getExternalCode(), c.getNameZhCn(), c.getNameEnUs(), c.getStatus()));
        }
        return result;
    }

    private void putLookupItem(Map<String, MdLookupItem> result, String key, MdLookupItem item) {
        if (key == null || key.isBlank() || item == null) {
            return;
        }
        result.put(key.trim(), item);
    }

    private static <S, T> PageResult<T> mapPage(IPage<S> source, Function<S, T> mapper) {
        Page<T> projected = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        projected.setRecords(source.getRecords().stream().map(mapper).collect(Collectors.toList()));
        return PageResult.of(projected);
    }
}
