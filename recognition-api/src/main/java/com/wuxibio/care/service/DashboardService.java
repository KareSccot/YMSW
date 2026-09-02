package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.common.enums.CommonStatus;
import com.wuxibio.care.common.enums.TemplateStatus;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.entity.TaskRun;
import com.wuxibio.care.entity.TaskTemplate;
import com.wuxibio.care.entity.TemplateChannelVariant;
import com.wuxibio.care.mapper.SysUserMapper;
import com.wuxibio.care.mapper.TaskRunMapper;
import com.wuxibio.care.mapper.TaskTemplateMapper;
import com.wuxibio.care.mapper.TemplateChannelVariantMapper;
import com.wuxibio.care.security.SecurityUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates dashboard statistics. Owns all data-fetch logic so the
 * controller layer never touches Mapper instances directly.
 */
@Service
public class DashboardService {

    private static final int RECENT_BATCH_LIMIT = 5;
    private static final String UNKNOWN_CHANNEL = "Unknown";

    private final TemplateChannelVariantMapper templateChannelVariantMapper;
    private final SysUserMapper sysUserMapper;
    private final TaskTemplateMapper taskTemplateMapper;
    private final TaskRunMapper taskRunMapper;

    public DashboardService(
            TemplateChannelVariantMapper templateChannelVariantMapper,
            SysUserMapper sysUserMapper,
            TaskTemplateMapper taskTemplateMapper,
            TaskRunMapper taskRunMapper) {
        this.templateChannelVariantMapper = templateChannelVariantMapper;
        this.sysUserMapper = sysUserMapper;
        this.taskTemplateMapper = taskTemplateMapper;
        this.taskRunMapper = taskRunMapper;
    }

    public Map<String, Object> buildStats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateCount", countPublishedTemplates());
        data.put("activeUserCount", countActiveUsers());
        data.put("configCount", countTaskTemplates());

        List<TaskRun> runs = listRunsByStartDesc();
        TaskRunTotals totals = aggregateTotals(runs);
        data.put("totalSent", totals.totalSent());
        data.put("totalSuccess", totals.totalSuccess());
        data.put("recentBatches", buildRecentBatches(runs));
        return data;
    }

    private long countPublishedTemplates() {
        return templateChannelVariantMapper.selectCount(
                new LambdaQueryWrapper<TemplateChannelVariant>()
                        .eq(TemplateChannelVariant::getStatus, TemplateStatus.Published.name()));
    }

    private long countActiveUsers() {
        return sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, CommonStatus.Active.name()));
    }

    private long countTaskTemplates() {
        return taskTemplateMapper.selectCount(new LambdaQueryWrapper<TaskTemplate>());
    }

    private List<TaskRun> listRunsByStartDesc() {
        String currentUsername = SecurityUtil.getCurrentUsername();
        boolean globalAdmin = SecurityUtil.isAdmin();
        LambdaQueryWrapper<TaskRun> wrapper =
                new LambdaQueryWrapper<TaskRun>().orderByDesc(TaskRun::getStartedAt);
        if (!globalAdmin) {
            if (currentUsername == null || currentUsername.isBlank()) {
                throw new BizException(401, "未登录");
            }
            wrapper.eq(TaskRun::getStartedBy, currentUsername);
        }
        List<TaskRun> runs = taskRunMapper.selectList(wrapper);
        if (globalAdmin) {
            return runs;
        }
        return runs.stream()
                .filter(run -> currentUsername.equals(run.getStartedBy()))
                .toList();
    }

    private TaskRunTotals aggregateTotals(List<TaskRun> runs) {
        int totalSent = 0;
        int totalSuccess = 0;
        for (TaskRun run : runs) {
            totalSent += nullSafe(run.getTotalCount());
            totalSuccess += nullSafe(run.getSuccessCount());
        }
        return new TaskRunTotals(totalSent, totalSuccess);
    }

    private List<Map<String, Object>> buildRecentBatches(List<TaskRun> runs) {
        List<Map<String, Object>> recent = new ArrayList<>();
        for (TaskRun run : runs.stream().limit(RECENT_BATCH_LIMIT).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.getId());
            row.put("channel", resolveRunChannel(run));
            row.put("status", run.getStatus());
            row.put("totalCount", run.getTotalCount());
            row.put("successCount", run.getSuccessCount());
            row.put("failCount", nullSafe(run.getFailedCount()) + nullSafe(run.getSuspendedCount()));
            row.put("createdAt", run.getStartedAt());
            recent.add(row);
        }
        return recent;
    }

    private String resolveRunChannel(TaskRun run) {
        if (run.getChannelVariantId() == null) return UNKNOWN_CHANNEL;
        TemplateChannelVariant variant = templateChannelVariantMapper.selectById(run.getChannelVariantId());
        return variant == null || variant.getChannel() == null ? UNKNOWN_CHANNEL : variant.getChannel();
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private record TaskRunTotals(int totalSent, int totalSuccess) {}
}
