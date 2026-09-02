package com.wuxibio.care.controller;

import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.MasterDataCompany;
import com.wuxibio.care.entity.MasterDataCountry;
import com.wuxibio.care.entity.MasterDataDepartment;
import com.wuxibio.care.entity.MasterDataRuleReference;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.MasterDataReferenceService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataReferenceController {

    private final MasterDataReferenceService service;
    private final FunctionPermissionGuard permissionGuard;

    public MasterDataReferenceController(MasterDataReferenceService service, FunctionPermissionGuard permissionGuard) {
        this.service = service;
        this.permissionGuard = permissionGuard;
    }

    @GetMapping("/companies")
    public R<PageResult<MasterDataCompany>> pageCompanies(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireAdmin();
        return R.ok(service.pageCompanies(page, size, keyword));
    }

    @PostMapping("/companies")
    public R<MasterDataCompany> createCompany(@RequestBody MasterDataCompany company) {
        requireAdmin();
        return R.ok(service.createCompany(company));
    }

    @PutMapping("/companies/{id}")
    public R<Void> updateCompany(@PathVariable("id") Long id, @RequestBody MasterDataCompany company) {
        requireAdmin();
        service.updateCompany(id, company);
        return R.ok(null);
    }

    @DeleteMapping("/companies/{id}")
    public R<Void> deleteCompany(@PathVariable("id") Long id) {
        requireAdmin();
        service.deleteCompany(id);
        return R.ok(null);
    }

    @PostMapping("/companies/sync")
    public R<MasterDataReferenceService.ReferenceSyncResult> syncCompanies() {
        requireAdmin();
        return R.ok(service.syncCompanies());
    }

    @GetMapping("/departments")
    public R<PageResult<MasterDataDepartment>> pageDepartments(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireAdmin();
        return R.ok(service.pageDepartments(page, size, keyword));
    }

    @PostMapping("/departments")
    public R<MasterDataDepartment> createDepartment(@RequestBody MasterDataDepartment department) {
        requireAdmin();
        return R.ok(service.createDepartment(department));
    }

    @PutMapping("/departments/{id}")
    public R<Void> updateDepartment(@PathVariable("id") Long id, @RequestBody MasterDataDepartment department) {
        requireAdmin();
        service.updateDepartment(id, department);
        return R.ok(null);
    }

    @DeleteMapping("/departments/{id}")
    public R<Void> deleteDepartment(@PathVariable("id") Long id) {
        requireAdmin();
        service.deleteDepartment(id);
        return R.ok(null);
    }

    @PostMapping("/departments/sync")
    public R<MasterDataReferenceService.ReferenceSyncResult> syncDepartments() {
        requireAdmin();
        return R.ok(service.syncDepartments());
    }

    @GetMapping("/countries")
    public R<PageResult<MasterDataCountry>> pageCountries(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireAdmin();
        return R.ok(service.pageCountries(page, size, keyword));
    }

    @PostMapping("/countries")
    public R<MasterDataCountry> createCountry(@RequestBody MasterDataCountry country) {
        requireAdmin();
        return R.ok(service.createCountry(country));
    }

    @PutMapping("/countries/{id}")
    public R<Void> updateCountry(@PathVariable("id") Long id, @RequestBody MasterDataCountry country) {
        requireAdmin();
        service.updateCountry(id, country);
        return R.ok(null);
    }

    @DeleteMapping("/countries/{id}")
    public R<Void> deleteCountry(@PathVariable("id") Long id) {
        requireAdmin();
        service.deleteCountry(id);
        return R.ok(null);
    }

    @PostMapping("/countries/sync")
    public R<MasterDataReferenceService.ReferenceSyncResult> syncCountries() {
        requireAdmin();
        return R.ok(service.syncCountries());
    }

    @GetMapping("/rule-references")
    public R<PageResult<MasterDataRuleReference>> pageRuleReferences(
            @RequestParam(name = "dimension") String dimension,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireAdmin();
        return R.ok(service.pageRuleReferences(dimension, page, size, keyword));
    }

    @PostMapping("/rule-references/{dimension}/sync")
    public R<MasterDataReferenceService.ReferenceSyncResult> syncRuleReferences(
            @PathVariable("dimension") String dimension) {
        requireAdmin();
        return R.ok(service.syncRuleReferences(dimension));
    }

    private void requireAdmin() {
        permissionGuard.requireAny(
                FunctionPermissionGuard.MASTER_DATA_MANAGE,
                FunctionPermissionGuard.USER_MANAGE);
    }
}
