package com.wuxibio.care.service;

import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceOwnershipTest {

    private TaskRunMapper taskRunMapper;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        TemplateChannelVariantMapper variantMapper = mock(TemplateChannelVariantMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        TaskTemplateMapper taskTemplateMapper = mock(TaskTemplateMapper.class);
        taskRunMapper = mock(TaskRunMapper.class);
        when(variantMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(taskTemplateMapper.selectCount(any())).thenReturn(0L);
        service = new DashboardService(variantMapper, userMapper, taskTemplateMapper, taskRunMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonGlobalAdminDashboardAggregatesOnlyOwnRuns() {
        authenticate(7L, "operator7", "ROLE_2");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(
                run(11L, "operator7", 3, 2),
                run(12L, "operator8", 9, 8)));

        Map<String, Object> result = service.buildStats();

        assertThat(result.get("totalSent")).isEqualTo(3);
        assertThat(result.get("totalSuccess")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) result.get("recentBatches");
        assertThat(recent).extracting(row -> row.get("id")).containsExactly(11L);
    }

    @Test
    void globalAdminDashboardAggregatesAllRuns() {
        authenticate(1L, "admin", "ROLE_GLOBAL_ADMIN");
        when(taskRunMapper.selectList(any())).thenReturn(List.of(
                run(11L, "operator7", 3, 2),
                run(12L, "operator8", 9, 8)));

        Map<String, Object> result = service.buildStats();

        assertThat(result.get("totalSent")).isEqualTo(12);
        assertThat(result.get("totalSuccess")).isEqualTo(10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) result.get("recentBatches");
        assertThat(recent).extracting(row -> row.get("id")).containsExactly(11L, 12L);
    }

    private TaskRun run(Long id, String startedBy, int total, int success) {
        TaskRun run = new TaskRun();
        run.setId(id);
        run.setStartedBy(startedBy);
        run.setTotalCount(total);
        run.setSuccessCount(success);
        run.setFailedCount(total - success);
        run.setSuspendedCount(0);
        return run;
    }

    private void authenticate(Long userId, String username, String authority) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(() -> authority));
        authentication.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
