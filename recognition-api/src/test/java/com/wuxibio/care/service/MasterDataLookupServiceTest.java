package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterDataLookupServiceTest {

    @Mock private MasterDataDepartmentMapper departmentMapper;
    @Mock private MasterDataCountryMapper countryMapper;
    @Mock private MasterDataCompanyMapper companyMapper;

    private MasterDataLookupService service() {
        return new MasterDataLookupService(departmentMapper, countryMapper, companyMapper);
    }

    @Test
    void searchDepartments_returnsMappedDto() {
        MasterDataDepartment row = new MasterDataDepartment();
        row.setExternalCode("D001");
        row.setNameZhCn("研发部");
        row.setNameEnUs("R&D");
        row.setStatus("Active");

        Page<MasterDataDepartment> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(row));
        when(departmentMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        PageResult<MdLookupItem> result = service().searchDepartments("研", 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        MdLookupItem item = result.getRecords().get(0);
        assertEquals("D001", item.getExternalCode());
        assertEquals("研发部", item.getLabelZh());
        assertEquals("R&D", item.getLabelEn());
        assertEquals("Active", item.getStatus());
    }

    @Test
    void searchCountries_usesLabelFields() {
        MasterDataCountry row = new MasterDataCountry();
        row.setExternalCode("CN");
        row.setLabelZhCn("中国");
        row.setLabelEnUs("China");
        row.setStatus("Active");

        Page<MasterDataCountry> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(row));
        when(countryMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);

        PageResult<MdLookupItem> result = service().searchCountries(null, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals("中国", result.getRecords().get(0).getLabelZh());
        assertEquals("China", result.getRecords().get(0).getLabelEn());
    }

    @Test
    void searchCompanies_blankKeywordSkipsLike() {
        Page<MasterDataCompany> empty = new Page<>(1, 20, 0);
        empty.setRecords(Collections.emptyList());
        when(companyMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(empty);

        PageResult<MdLookupItem> result = service().searchCompanies("   ", 1, 20);

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void batchLookupByCodes_emptyInputReturnsEmptyMap_withoutHittingDb() {
        Map<String, MdLookupItem> result = service().batchLookupByCodes(
                MasterDataLookupService.DIMENSION_DEPARTMENT,
                Collections.emptyList());
        assertTrue(result.isEmpty());
        verify(departmentMapper, never()).selectList(any());
    }

    @Test
    void batchLookupByCodes_unknownDimensionReturnsEmpty() {
        Map<String, MdLookupItem> result = service().batchLookupByCodes(
                "unknown",
                List.of("X"));
        assertTrue(result.isEmpty());
        verify(departmentMapper, never()).selectList(any());
        verify(countryMapper, never()).selectList(any());
        verify(companyMapper, never()).selectList(any());
    }

    @Test
    void batchLookupByCodes_departmentReturnsCodeToItem() {
        MasterDataDepartment row = new MasterDataDepartment();
        row.setExternalCode("D001");
        row.setNameZhCn("研发部");
        row.setNameEnUs("R&D");
        row.setStatus("Active");
        when(departmentMapper.selectList(any())).thenReturn(List.of(row));

        Map<String, MdLookupItem> result = service().batchLookupByCodes(
                MasterDataLookupService.DIMENSION_DEPARTMENT,
                List.of("D001", "D002"));

        assertEquals(1, result.size());
        MdLookupItem item = result.get("D001");
        assertNotNull(item);
        assertEquals("研发部", item.getLabelZh());
        assertNull(result.get("D002"));
    }

    @Test
    void batchLookupByCodes_countryReturnsExternalCodeAndOptionIdKeys() {
        MasterDataCountry row = new MasterDataCountry();
        row.setExternalCode("CN");
        row.setOptionId("156");
        row.setLabelZhCn("中国");
        row.setLabelEnUs("China");
        row.setStatus("A");
        when(countryMapper.selectList(any())).thenReturn(List.of(row));

        Map<String, MdLookupItem> result = service().batchLookupByCodes(
                MasterDataLookupService.DIMENSION_COUNTRY,
                List.of("156"));

        assertEquals("中国", result.get("CN").getLabelZh());
        assertEquals("中国", result.get("156").getLabelZh());
    }

    @Test
    void batchLookupByCodes_trimsAndDedupsInputCodes() {
        when(departmentMapper.selectList(any())).thenReturn(List.of());
        service().batchLookupByCodes(
                MasterDataLookupService.DIMENSION_DEPARTMENT,
                Arrays.asList("D001", " D001 ", "D001", null, "  "));
        verify(departmentMapper).selectList(any());
    }

    @Test
    void expandDepartmentCodes_includesAllDescendantsAndStopsCycles() {
        MasterDataDepartment root = department("BIO", null);
        MasterDataDepartment child = department("RND", "BIO");
        MasterDataDepartment grandchild = department("LAB", "RND");
        MasterDataDepartment cycle = department("BIO", "LAB");
        when(departmentMapper.selectList(any())).thenReturn(List.of(root, child, grandchild, cycle));

        List<String> result = service().expandDepartmentCodes(List.of("BIO"));

        assertEquals(List.of("BIO", "RND", "LAB"), result);
    }

    private MasterDataDepartment department(String code, String parentCode) {
        MasterDataDepartment row = new MasterDataDepartment();
        row.setExternalCode(code);
        row.setParentExternalCode(parentCode);
        row.setStatus("Active");
        return row;
    }
}
