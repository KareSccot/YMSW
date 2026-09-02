package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.dto.SenderMailboxRequest;
import com.wuxibio.care.dto.SenderMailboxResponse;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.SenderMailboxService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sender-mailboxes")
public class SenderMailboxController {

    private final SenderMailboxService service;

    public SenderMailboxController(SenderMailboxService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission({
            FunctionPermissionGuard.SENDER_MAILBOX_CREATE,
            FunctionPermissionGuard.SENDER_MAILBOX_EDIT,
            FunctionPermissionGuard.SENDER_MAILBOX_DELETE,
            FunctionPermissionGuard.SENDER_MAILBOX_TEST,
            FunctionPermissionGuard.CONNECTION_CREATE,
            FunctionPermissionGuard.CONNECTION_EDIT,
            FunctionPermissionGuard.CONNECTION_DELETE,
            FunctionPermissionGuard.CONNECTION_TEST
    })
    public R<List<SenderMailboxResponse>> list() {
        return R.ok(service.listAll());
    }

    @GetMapping("/{id}")
    @RequiresPermission({
            FunctionPermissionGuard.SENDER_MAILBOX_CREATE,
            FunctionPermissionGuard.SENDER_MAILBOX_EDIT,
            FunctionPermissionGuard.SENDER_MAILBOX_DELETE,
            FunctionPermissionGuard.SENDER_MAILBOX_TEST,
            FunctionPermissionGuard.CONNECTION_CREATE,
            FunctionPermissionGuard.CONNECTION_EDIT,
            FunctionPermissionGuard.CONNECTION_DELETE,
            FunctionPermissionGuard.CONNECTION_TEST
    })
    public R<SenderMailboxResponse> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    @RequiresPermission({FunctionPermissionGuard.SENDER_MAILBOX_CREATE, FunctionPermissionGuard.CONNECTION_CREATE})
    public R<SenderMailboxResponse> create(@RequestBody SenderMailboxRequest mailbox) {
        return R.ok(service.create(mailbox));
    }

    @PutMapping("/{id}")
    @RequiresPermission({FunctionPermissionGuard.SENDER_MAILBOX_EDIT, FunctionPermissionGuard.CONNECTION_EDIT})
    public R<SenderMailboxResponse> update(@PathVariable("id") Long id, @RequestBody SenderMailboxRequest mailbox) {
        return R.ok(service.update(id, mailbox));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({FunctionPermissionGuard.SENDER_MAILBOX_DELETE, FunctionPermissionGuard.CONNECTION_DELETE})
    public R<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @RequiresPermission({FunctionPermissionGuard.SENDER_MAILBOX_TEST, FunctionPermissionGuard.CONNECTION_TEST})
    public R<Map<String, Object>> test(@PathVariable("id") Long id) {
        return R.ok(service.test(id));
    }
}
