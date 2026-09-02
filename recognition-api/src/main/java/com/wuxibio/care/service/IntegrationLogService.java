package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuxibio.care.entity.IntegrationLog;
import com.wuxibio.care.mapper.IntegrationLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IntegrationLogService {

    private final IntegrationLogMapper integrationLogMapper;

    public IntegrationLogService(IntegrationLogMapper integrationLogMapper) {
        this.integrationLogMapper = integrationLogMapper;
    }

    public void log(
            String integrationType,
            String endpoint,
            String requestPayload,
            String responsePayload,
            String resultStatus,
            String errorMessage) {
        IntegrationLog logRow = new IntegrationLog();
        logRow.setIntegrationType(integrationType);
        logRow.setEndpoint(endpoint);
        logRow.setRequestPayload(requestPayload);
        logRow.setResponsePayload(responsePayload);
        logRow.setResultStatus(resultStatus);
        logRow.setErrorMessage(errorMessage);
        logRow.setCreatedAt(LocalDateTime.now());
        integrationLogMapper.insert(logRow);
    }

    public Map<String, Object> page(int page, int size, String integrationType, String resultStatus) {
        int current = Math.max(1, page);
        int pageSize = Math.max(1, size);

        LambdaQueryWrapper<IntegrationLog> wrapper = new LambdaQueryWrapper<>();
        if (integrationType != null && !integrationType.isBlank()) {
            wrapper.eq(IntegrationLog::getIntegrationType, integrationType.trim());
        }
        if (resultStatus != null && !resultStatus.isBlank()) {
            wrapper.eq(IntegrationLog::getResultStatus, resultStatus.trim());
        }
        wrapper.orderByDesc(IntegrationLog::getId);

        List<IntegrationLog> all = integrationLogMapper.selectList(wrapper);
        int fromIndex = (current - 1) * pageSize;
        int toIndex = Math.min(all.size(), fromIndex + pageSize);
        List<IntegrationLog> records = fromIndex >= all.size() ? List.of() : all.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", current);
        result.put("size", pageSize);
        return result;
    }
}

