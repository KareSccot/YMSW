package com.wuxibio.care.controller;

import com.wuxibio.care.common.R;
import com.wuxibio.care.service.RunCenterService;
import com.wuxibio.care.service.TaskGovernanceService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunCenterControllerTest {

    @Test
    void pageEnrichesPendingApprovalRunWithCurrentApprovers() {
        RunCenterService runCenterService = mock(RunCenterService.class);
        TaskGovernanceService taskGovernanceService = mock(TaskGovernanceService.class);
        RunCenterController controller = new RunCenterController(runCenterService, taskGovernanceService);
        Map<String, Object> pendingRun = new LinkedHashMap<>();
        pendingRun.put("id", 91L);
        pendingRun.put("statusNormalized", "Pending_Approval");
        Map<String, Object> completedRun = new LinkedHashMap<>();
        completedRun.put("id", 92L);
        completedRun.put("statusNormalized", "Completed");
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", List.of(pendingRun, completedRun));
        page.put("total", 2);
        List<Map<String, Object>> approvers = List.of(Map.of(
                "name", "Alice",
                "employeeId", "E1001"));

        when(runCenterService.pageRuns(1, 20, null, null, null)).thenReturn(page);
        when(taskGovernanceService.currentApproversByTaskRunIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvers));

        R<Map<String, Object>> response = controller.page(1, 20, null, null, null);

        assertEquals(approvers, pendingRun.get("currentApprovers"));
        assertEquals(List.of(), completedRun.get("currentApprovers"));
        assertEquals(page, response.getData());
        verify(taskGovernanceService).currentApproversByTaskRunIds(List.of(91L));
    }
}
