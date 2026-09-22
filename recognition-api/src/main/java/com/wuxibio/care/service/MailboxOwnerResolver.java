package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MailboxOwnerResolver {

    private final SysUserMapper sysUserMapper;

    public MailboxOwnerResolver(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    public SysUser requireByEmployeeId(String rawEmployeeId) {
        String employeeId = rawEmployeeId == null ? "" : rawEmployeeId.trim();
        if (employeeId.isBlank()) throw new BizException("邮箱 Owner 不能为空");
        SysUser user = sysUserMapper.selectApprovalCandidateByEmployeeId(
                employeeId,
                MasterDataSyncService.EMPLOYEE_ROLE_NAME);
        if (user == null) {
            throw new BizException("邮箱 Owner 必须是可登录的后台用户 (employee_id=" + employeeId + ")");
        }
        if (!isActive(user)) {
            throw new BizException("邮箱 Owner 账号已停用 (employee_id=" + employeeId + ")");
        }
        return user;
    }

    public SysUser findByEmployeeId(String rawEmployeeId) {
        if (rawEmployeeId == null || rawEmployeeId.isBlank()) return null;
        return sysUserMapper.selectApprovalCandidateByEmployeeId(
                rawEmployeeId.trim(),
                MasterDataSyncService.EMPLOYEE_ROLE_NAME);
    }

    public boolean isEligibleEmployeeId(String rawEmployeeId) {
        SysUser user = findByEmployeeId(rawEmployeeId);
        return user != null && isActive(user);
    }

    public List<OwnerOption> search(String keyword, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return sysUserMapper.selectApprovalCandidates(
                        keyword == null ? null : keyword.trim(),
                        null,
                        MasterDataSyncService.EMPLOYEE_ROLE_NAME,
                        safeLimit)
                .stream()
                .filter(this::isActive)
                .map(user -> new OwnerOption(
                        user.getId(),
                        user.getEmployeeId(),
                        user.getName(),
                        user.getUsername(),
                        user.getEmail()))
                .toList();
    }

    private boolean isActive(SysUser user) {
        String status = user == null || user.getStatus() == null ? "" : user.getStatus().trim();
        return "Active".equalsIgnoreCase(status) || "SYNCED".equalsIgnoreCase(status);
    }

    public record OwnerOption(Long userId, String employeeId, String name, String username, String email) {}
}
