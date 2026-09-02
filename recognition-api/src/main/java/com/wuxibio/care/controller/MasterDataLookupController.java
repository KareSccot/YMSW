package com.wuxibio.care.controller;

import com.wuxibio.care.common.PageResult;
import com.wuxibio.care.common.R;
import com.wuxibio.care.dto.MdLookupItem;
import com.wuxibio.care.service.MasterDataLookupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only master-data lookup endpoints for searchable dropdowns.
 *
 * <p>Distinct from {@link MasterDataReferenceController} (admin CRUD + sync):
 * these endpoints return a compact DTO with only externalCode + labels +
 * status, filter by status='Active', and are available to any authenticated
 * user — they back UI dropdowns like the Target Group condition picker.
 */
@RestController
@RequestMapping("/api/v1/master-data/lookup")
public class MasterDataLookupController {

    private final MasterDataLookupService lookupService;

    public MasterDataLookupController(MasterDataLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/departments")
    public R<PageResult<MdLookupItem>> searchDepartments(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return R.ok(lookupService.searchDepartments(keyword, page, size));
    }

    @GetMapping("/countries")
    public R<PageResult<MdLookupItem>> searchCountries(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return R.ok(lookupService.searchCountries(keyword, page, size));
    }

    @GetMapping("/companies")
    public R<PageResult<MdLookupItem>> searchCompanies(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return R.ok(lookupService.searchCompanies(keyword, page, size));
    }
}
