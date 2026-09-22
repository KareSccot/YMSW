package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SenderMailboxBindingApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SenderMailboxBindingApprovalController {

    private final SenderMailboxBindingApprovalService service;

    public SenderMailboxBindingApprovalController(SenderMailboxBindingApprovalService service) {
        this.service = service;
    }

    @PostMapping("/template-headers/{headerId}/sender-mailbox-binding-requests")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<SenderMailboxBindingApprovalService.BindingActionResult> request(
            @PathVariable("headerId") Long headerId,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.request(headerId, asLong(body.get("senderMailboxId")), asString(body.get("comment"))));
    }

    @GetMapping("/template-headers/{headerId}/sender-mailbox-binding-state")
    @RequiresPermission(FunctionPermissionGuard.TEMPLATE_MANAGE)
    public R<SenderMailboxBindingApprovalService.BindingState> bindingState(
            @PathVariable("headerId") Long headerId) {
        return R.ok(service.bindingState(headerId));
    }

    @GetMapping("/sender-mailbox-binding-approvals")
    public R<Map<String, Object>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "scope", defaultValue = "assigned") String scope,
            @RequestParam(name = "status", required = false) String status) {
        return R.ok(service.page(page, size, scope, status));
    }

    @GetMapping("/sender-mailbox-binding-approvals/{id}")
    public R<SenderMailboxBindingApprovalService.RequestView> get(@PathVariable("id") Long id) {
        return R.ok(service.get(id));
    }

    @PostMapping("/sender-mailbox-binding-approvals/{id}/decision")
    public R<SenderMailboxBindingApprovalService.RequestView> decide(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> body) {
        return R.ok(service.decide(id, asString(body.get("decision")), asString(body.get("comment"))));
    }

    @PostMapping("/sender-mailbox-binding-approvals/{id}/cancel")
    public R<SenderMailboxBindingApprovalService.RequestView> cancel(
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        return R.ok(service.cancel(id, body == null ? null : asString(body.get("reason"))));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
