package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TargetGroup;
import com.wuxibio.care.entity.TargetGroupCondition;
import com.wuxibio.care.security.RequiresPermission;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TargetGroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/target-groups")
public class TargetGroupController {

    private final TargetGroupService service;

    public TargetGroupController(TargetGroupService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_VIEW)
    public R<List<TargetGroup>> list() {
        return R.ok(service.listAll());
    }

    @GetMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_VIEW)
    public R<TargetGroup> getById(@PathVariable("id") Long id) {
        return R.ok(service.getById(id));
    }

    @GetMapping("/{id}/conditions")
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_EDIT)
    public R<List<TargetGroupCondition>> getConditions(@PathVariable("id") Long id) {
        return R.ok(service.getConditions(id));
    }

    @GetMapping("/{id}/members")
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_MEMBERS)
    public R<List<SysUser>> getMembers(@PathVariable("id") Long id) {
        return R.ok(service.getMembers(id));
    }

    @PostMapping
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_CREATE)
    public R<Void> create(@RequestBody Map<String, Object> body) {
        TargetGroup group = new TargetGroup();
        group.setName((String) body.get("name"));
        group.setDescription((String) body.get("description"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> conditionsRaw = (List<Map<String, String>>) body.get("conditions");
        List<TargetGroupCondition> conditions = conditionsRaw != null ?
                conditionsRaw.stream().map(c -> {
                    TargetGroupCondition tc = new TargetGroupCondition();
                    tc.setDimension(c.get("dimension"));
                    tc.setValue(c.get("value"));
                    return tc;
                }).toList() : null;

        service.create(group, conditions);
        return R.ok();
    }

    @PutMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_EDIT)
    public R<Void> update(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        TargetGroup group = new TargetGroup();
        group.setName((String) body.get("name"));
        group.setDescription((String) body.get("description"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> conditionsRaw = (List<Map<String, String>>) body.get("conditions");
        List<TargetGroupCondition> conditions = conditionsRaw != null ?
                conditionsRaw.stream().map(c -> {
                    TargetGroupCondition tc = new TargetGroupCondition();
                    tc.setDimension(c.get("dimension"));
                    tc.setValue(c.get("value"));
                    return tc;
                }).toList() : null;

        service.update(id, group, conditions);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(FunctionPermissionGuard.TARGET_GROUP_DELETE)
    public R<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return R.ok();
    }
}
