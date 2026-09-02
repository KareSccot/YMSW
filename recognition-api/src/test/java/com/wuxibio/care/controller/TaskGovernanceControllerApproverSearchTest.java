package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.service.ApprovalWorkflowService;
import com.wuxibio.care.service.FunctionPermissionGuard;
import com.wuxibio.care.service.TaskGovernanceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskGovernanceControllerApproverSearchTest {

    @Test
    void searchApproverUsers_includesSyncedUserWithNonEmployeeRole() {
        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        TaskGovernanceController controller = new TaskGovernanceController(
                mock(TaskGovernanceService.class),
                mock(ApprovalWorkflowService.class),
                mock(FunctionPermissionGuard.class),
                sysUserMapper);
        SysUser user = new SysUser();
        user.setId(943490L);
        user.setEmployeeId("30006057");
        user.setUsername("30006057");
        user.setName("FirstN7755 LastN7755");
        user.setEmail("yao.shuteng.ext@wuxibiologics.com");
        user.setStatus("SYNCED");
        when(sysUserMapper.selectApprovalCandidates("30006057", null, "Employee", 20))
                .thenReturn(List.of(user));

        R<List<Map<String, Object>>> result = controller.searchApproverUsers(" 30006057 ", " ", 20);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("30006057", result.getData().get(0).get("employeeId"));
        assertEquals("SYNCED", result.getData().get(0).get("status"));
        verify(sysUserMapper).selectApprovalCandidates("30006057", null, "Employee", 20);
    }
}
