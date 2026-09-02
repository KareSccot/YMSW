package com.wuxibio.care.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.SysUser;
import com.wuxibio.care.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DingTalkDirectorySyncService {

    private static final String DEFAULT_API_BASE_URL = "https://oapi.dingtalk.com";
    private static final long DEFAULT_ROOT_DEPT_ID = 1L;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final ExternalConnectionService connectionService;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public record SyncResult(
            int totalUsers,
            int matchedUsers,
            int updated,
            int unchanged,
            int skippedNoEmployeeId,
            int unmatchedUsers,
            String message) {
    }

    private record DingTalkDirectoryUser(String userId, String employeeId) {
    }

    private record DirectoryFetchResult(Map<String, DingTalkDirectoryUser> users, String limitedReason) {
    }

    private record DepartmentSelection(Set<Long> departmentIds, String limitedReason) {
    }

    public DingTalkDirectorySyncService(ExternalConnectionService connectionService, SysUserMapper sysUserMapper) {
        this.connectionService = connectionService;
        this.sysUserMapper = sysUserMapper;
    }

    @Transactional
    public SyncResult syncUserIdsByEmployeeId() {
        Map<String, String> cfg = connectionService.getActiveConfig("DingTalk");
        if (cfg == null) {
            throw new BizException("没有激活的钉钉连接");
        }
        String appKey = requireConfig(cfg, "appKey");
        String appSecret = requireConfig(cfg, "appSecret");
        String apiBaseUrl = normalizeApiBaseUrl(cfg.get("apiBaseUrl"));
        String accessToken = fetchAccessToken(apiBaseUrl, appKey, appSecret);

        DirectoryFetchResult fetchResult = fetchDirectoryUsers(apiBaseUrl, accessToken, cfg);
        Map<String, DingTalkDirectoryUser> dingTalkUsers = fetchResult.users();
        Map<String, SysUser> localUsers = listLocalUsersByEmployeeId();

        int matched = 0;
        int updated = 0;
        int unchanged = 0;
        int skippedNoEmployeeId = 0;
        int unmatched = 0;

        for (DingTalkDirectoryUser dingTalkUser : dingTalkUsers.values()) {
            String employeeId = normalize(dingTalkUser.employeeId());
            String userId = normalize(dingTalkUser.userId());
            if (employeeId.isBlank() || userId.isBlank()) {
                skippedNoEmployeeId++;
                continue;
            }
            SysUser localUser = localUsers.get(normalizeKey(employeeId));
            if (localUser == null) {
                unmatched++;
                continue;
            }
            matched++;
            String current = normalize(localUser.getDingtalkUserId());
            if (Objects.equals(current, userId)) {
                unchanged++;
                continue;
            }
            SysUser update = new SysUser();
            update.setId(localUser.getId());
            update.setDingtalkUserId(userId);
            sysUserMapper.updateById(update);
            updated++;
        }

        String message = "钉钉ID同步完成：钉钉用户 " + dingTalkUsers.size()
                + "，匹配本地人员 " + matched
                + "，更新 " + updated
                + "，不变 " + unchanged
                + "，无工号 " + skippedNoEmployeeId
                + "，未匹配 " + unmatched
                + (fetchResult.limitedReason().isBlank() ? "" : "。" + fetchResult.limitedReason());
        return new SyncResult(dingTalkUsers.size(), matched, updated, unchanged, skippedNoEmployeeId, unmatched, message);
    }

    private DirectoryFetchResult fetchDirectoryUsers(String apiBaseUrl, String accessToken, Map<String, String> cfg) {
        DepartmentSelection departmentSelection = resolveDepartmentIds(apiBaseUrl, accessToken, cfg);
        Set<Long> departmentIds = departmentSelection.departmentIds();
        int pageSize = parsePositiveInt(cfg.get("contactPageSize"), DEFAULT_PAGE_SIZE, 1, 100);
        Map<String, DingTalkDirectoryUser> byUserId = new LinkedHashMap<>();
        for (Long deptId : departmentIds) {
            long cursor = 0;
            boolean hasMore;
            do {
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("dept_id", deptId);
                request.put("cursor", cursor);
                request.put("size", pageSize);
                request.put("language", "zh_CN");
                Map<String, Object> response = postJson(apiBaseUrl + "/topapi/v2/user/list?access_token=" + urlEncode(accessToken), request);
                Map<String, Object> result = objectMap(response.get("result"));
                List<Object> users = objectList(result.get("list"));
                for (Object rawUser : users) {
                    Map<String, Object> user = objectMap(rawUser);
                    String userId = firstNonBlank(
                            stringValue(user.get("userid")),
                            stringValue(user.get("user_id")),
                            stringValue(user.get("userId")));
                    String employeeId = firstNonBlank(
                            stringValue(user.get("job_number")),
                            stringValue(user.get("jobnumber")),
                            stringValue(user.get("jobNumber")),
                            extensionValue(user.get("extension"), "job_number"),
                            extensionValue(user.get("extension"), "jobnumber"));
                    if (!normalize(userId).isBlank()) {
                        byUserId.putIfAbsent(userId, new DingTalkDirectoryUser(userId, employeeId));
                    }
                }
                hasMore = booleanValue(result.get("has_more"));
                Object nextCursor = result.get("next_cursor");
                cursor = nextCursor instanceof Number number ? number.longValue() : cursor + users.size();
            } while (hasMore);
        }
        return new DirectoryFetchResult(byUserId, departmentSelection.limitedReason());
    }

    private DepartmentSelection resolveDepartmentIds(String apiBaseUrl, String accessToken, Map<String, String> cfg) {
        Set<Long> configured = parseDepartmentIds(cfg.get("contactDepartmentIds"));
        if (!configured.isEmpty()) {
            return new DepartmentSelection(configured, "");
        }

        long rootDeptId = parseLong(cfg.get("contactRootDeptId"), DEFAULT_ROOT_DEPT_ID);
        Set<Long> visited = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(rootDeptId);
        while (!queue.isEmpty()) {
            Long deptId = queue.removeFirst();
            if (!visited.add(deptId)) {
                continue;
            }
            Map<String, Object> response;
            try {
                response = postJson(
                        apiBaseUrl + "/topapi/v2/department/listsubid?access_token=" + urlEncode(accessToken),
                        Map.of("dept_id", deptId));
            } catch (BizException e) {
                if (isMissingDepartmentListPermission(e)) {
                    return new DepartmentSelection(
                            Set.of(rootDeptId),
                            "钉钉应用未开通部门列表权限，仅同步根部门 " + rootDeptId + "；如需全量同步请开通 qyapi_get_department_list 或配置指定同步部门ID");
                }
                throw e;
            }
            List<Object> childIds = objectList(objectMap(response.get("result")).get("dept_id_list"));
            for (Object childId : childIds) {
                Long parsed = numberAsLong(childId);
                if (parsed != null && !visited.contains(parsed)) {
                    queue.addLast(parsed);
                }
            }
        }
        return new DepartmentSelection(visited, "");
    }

    private boolean isMissingDepartmentListPermission(BizException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("qyapi_get_department_list") || message.contains("requiredScopes");
    }

    private Map<String, SysUser> listLocalUsersByEmployeeId() {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .isNotNull(SysUser::getEmployeeId)
                .ne(SysUser::getEmployeeId, "")
                .orderByAsc(SysUser::getId));
        Map<String, SysUser> byEmployeeId = new LinkedHashMap<>();
        for (SysUser user : users) {
            String key = normalizeKey(user.getEmployeeId());
            if (!key.isBlank()) {
                byEmployeeId.putIfAbsent(key, user);
            }
        }
        return byEmployeeId;
    }

    private String fetchAccessToken(String apiBaseUrl, String appKey, String appSecret) {
        String url = apiBaseUrl + "/gettoken?appkey=" + urlEncode(appKey) + "&appsecret=" + urlEncode(appSecret);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        Map<String, Object> body = send(request);
        return requireResponseString(body, "access_token", "获取钉钉 access_token 失败");
    }

    private Map<String, Object> postJson(String url, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用钉钉通讯录接口失败: " + e.getMessage());
        }
    }

    private Map<String, Object> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("调用钉钉接口失败，HTTP " + response.statusCode());
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            int errcode = body.get("errcode") instanceof Number number ? number.intValue() : -1;
            if (errcode != 0) {
                throw new BizException("调用钉钉接口失败: " + body.getOrDefault("errmsg", response.body()));
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用钉钉接口失败: " + e.getMessage());
        }
    }

    private String requireResponseString(Map<String, Object> body, String key, String message) {
        String value = stringValue(body.get(key));
        if (value == null || value.isBlank()) {
            throw new BizException(message + ": " + body);
        }
        return value;
    }

    private String requireConfig(Map<String, String> cfg, String key) {
        String value = normalize(cfg.get(key));
        if (value.isBlank()) {
            throw new BizException("钉钉连接缺少配置项: " + key);
        }
        return value;
    }

    private Set<Long> parseDepartmentIds(String raw) {
        Set<Long> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[,;\\n\\r]+")) {
            String text = part.trim();
            if (text.isBlank()) {
                continue;
            }
            try {
                result.add(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new BizException("钉钉同步部门ID非法: " + text);
            }
        }
        return result;
    }

    private int parsePositiveInt(String raw, int defaultValue, int min, int max) {
        try {
            int value = raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long parseLong(String raw, long defaultValue) {
        try {
            return raw == null || raw.isBlank() ? defaultValue : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Long numberAsLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extensionValue(Object extension, String key) {
        if (extension instanceof Map<?, ?> map) {
            return stringValue(map.get(key));
        }
        if (extension instanceof String raw && raw.trim().startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
                return stringValue(parsed.get(key));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            raw.forEach((key, item) -> map.put(String.valueOf(key), item));
            return map;
        }
        return Map.of();
    }

    private List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeApiBaseUrl(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return DEFAULT_API_BASE_URL;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeKey(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
