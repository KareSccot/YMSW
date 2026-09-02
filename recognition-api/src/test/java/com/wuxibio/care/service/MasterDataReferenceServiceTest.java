package com.wuxibio.care.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verify date-format normalization that we apply when SuccessFactors OData
 * returns "/Date(epochMillis)/" instead of an ISO date.
 */
class MasterDataReferenceServiceTest {

    @Test
    void normalizeStartDate_parsesOdataEpochMillis() {
        // 1626220800000 = 2021-07-14 08:00:00 UTC = 2021-07-14 16:00:00 Asia/Shanghai
        // Date portion (Shanghai zone) is 2021-07-14.
        assertEquals("2021-07-14", MasterDataReferenceService.normalizeStartDate("/Date(1626220800000)/"));
    }

    @Test
    void normalizeStartDate_handlesTrailingOffset() {
        assertEquals("2021-07-14", MasterDataReferenceService.normalizeStartDate("/Date(1626220800000+0800)/"));
    }

    @Test
    void normalizeStartDate_keepsAlreadyIsoFormat() {
        assertEquals("2024-12-25", MasterDataReferenceService.normalizeStartDate("2024-12-25"));
    }

    @Test
    void normalizeStartDate_truncatesIsoDateTime() {
        assertEquals("2024-12-25", MasterDataReferenceService.normalizeStartDate("2024-12-25T08:00:00"));
    }

    @Test
    void normalizeStartDate_blankReturnsNull() {
        assertNull(MasterDataReferenceService.normalizeStartDate(""));
        assertNull(MasterDataReferenceService.normalizeStartDate("   "));
        assertNull(MasterDataReferenceService.normalizeStartDate(null));
    }

    @Test
    void normalizeStartDate_passesThroughUnknownFormat() {
        // Don't lose data; if we don't recognize it, return as-is for human inspection.
        assertEquals("Jan 1 2020", MasterDataReferenceService.normalizeStartDate("Jan 1 2020"));
    }

    @Test
    void mapsEmployeeTypePickListFields() {
        MasterDataReferenceService.RuleReferenceValues values = MasterDataReferenceService.parseRuleReferenceRow(
                "employeeType",
                Map.of(
                        "externalCode", "1",
                        "PickListV2_effectiveStartDate", "/Date(-2208988800000)/",
                        "label_zh_CN", "正式员工",
                        "label_en_US", "Regular Employee",
                        "optionId", "1001",
                        "status", "A"));

        assertEquals("1", values.code());
        assertEquals("1900-01-01", values.startDate());
        assertEquals("正式员工", values.labelZhCn());
        assertEquals("Regular Employee", values.labelEnUs());
        assertEquals("1001", values.optionId());
        assertEquals("A", values.status());
    }

    @Test
    void mapsPositionCodeAndLocalizedNames() {
        MasterDataReferenceService.RuleReferenceValues values = MasterDataReferenceService.parseRuleReferenceRow(
                "jobTitle",
                Map.of(
                        "code", "P100",
                        "effectiveStartDate", "/Date(1574208000000)/",
                        "effectiveEndDate", "/Date(253402214400000)/",
                        "externalName_zh_CN", "人力资源专员",
                        "externalName_en_US", "HR Specialist",
                        "effectiveStatus", "A"));

        assertEquals("P100", values.code());
        assertEquals("2019-11-20", values.startDate());
        assertEquals("人力资源专员", values.labelZhCn());
        assertEquals("HR Specialist", values.labelEnUs());
        assertEquals("A", values.status());
    }

    @Test
    void mapsDivisionWithoutTenantSpecificParentField() {
        MasterDataReferenceService.RuleReferenceValues values = MasterDataReferenceService.parseRuleReferenceRow(
                "division",
                Map.of(
                        "externalCode", "BIO",
                        "startDate", "/Date(1574208000000)/",
                        "endDate", "/Date(253402214400000)/",
                        "name_zh_CN", "生物事业部",
                        "name_en_US", "Biologics",
                        "status", "A"));

        assertEquals("BIO", values.code());
        assertEquals("生物事业部", values.labelZhCn());
        assertEquals("Biologics", values.labelEnUs());
        assertEquals("A", values.status());
        assertNull(values.parentExternalCode());
    }

    @Test
    void mapsCustomOrganizationMdfFieldsForLevelsThreeToFive() {
        for (String dimension : new String[] {"thirdDepartment", "fourthDepartment", "fifthDepartment"}) {
            MasterDataReferenceService.RuleReferenceValues values = MasterDataReferenceService.parseRuleReferenceRow(
                    dimension,
                    Map.of(
                            "externalCode", "ORG-001",
                            "effectiveStartDate", "/Date(1574208000000)/",
                            "mdfSystemEffectiveEndDate", "/Date(253402214400000)/",
                            "externalName_zh_CN", "研发组织",
                            "externalName_en_US", "Research Organization",
                            "mdfSystemStatus", "A"));

            assertEquals("ORG-001", values.code());
            assertEquals("2019-11-20", values.startDate());
            assertEquals("9999-12-31", values.endDate());
            assertEquals("研发组织", values.labelZhCn());
            assertEquals("Research Organization", values.labelEnUs());
            assertEquals("A", values.status());
        }
    }

    @Test
    void rejectsUnsupportedRuleReferenceDimension() {
        assertThrows(IllegalArgumentException.class,
                () -> MasterDataReferenceService.parseRuleReferenceRow("event", Map.of()));
    }

    @Test
    void addsActiveStatusFilterWhenQueryHasNoFilter() {
        assertEquals(
                "/odata/v2/Position?$select=code,effectiveStatus&$filter=effectiveStatus eq 'A'",
                MasterDataReferenceService.ensureActiveStatusFilter(
                        "/odata/v2/Position?$select=code,effectiveStatus", "effectiveStatus"));
    }

    @Test
    void combinesActiveStatusWithExistingPickListFilter() {
        assertEquals(
                "/odata/v2/PickListValueV2?$filter=PickListV2_id eq 'EmployeeType' and status eq 'A'",
                MasterDataReferenceService.ensureActiveStatusFilter(
                        "/odata/v2/PickListValueV2?$filter=PickListV2_id eq 'EmployeeType'", "status"));
    }

    @Test
    void insertsActiveStatusBeforeFollowingQueryParameter() {
        assertEquals(
                "/odata/v2/FODivision?$filter=name eq 'BIO' and status eq 'A'&$select=externalCode,status",
                MasterDataReferenceService.ensureActiveStatusFilter(
                        "/odata/v2/FODivision?$filter=name eq 'BIO'&$select=externalCode,status", "status"));
    }

    @Test
    void doesNotDuplicateExistingActiveStatusFilter() {
        String query = "/odata/v2/FOLocation?$filter=status eq 'A'&$select=externalCode,status";
        assertEquals(query, MasterDataReferenceService.ensureActiveStatusFilter(query, "status"));
    }
}
