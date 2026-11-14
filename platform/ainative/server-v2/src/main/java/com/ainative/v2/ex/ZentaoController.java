package com.ainative.v2.ex;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 禅道连接页专用端点(前端 zentaoApi.ts 契约)。
 *
 * <p>连接测试与项目搜索直接打 {baseUrl}/api.php/v1/*(Token 头);
 * 绑定落 ex_binding(provider=ZENTAO,project_id=外部项目 ID,config_json 存 baseUrl,
 * credential_ref 存 Token),拉取同步走既有 ExPullService。
 */
@RestController
@RequestMapping("/api/integrations/zentao")
public class ZentaoController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public ZentaoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BindingRequest(String baseUrl, String account, String password,
                                 String token, String externalProjectId,
                                 String externalProjectName, Integer pollIntervalSeconds, Boolean enabled) {}

    @PostMapping("/test")
    public ApiResult<Map<String, Object>> test(@RequestBody BindingRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            out.put("success", false);
            out.put("message", "缺少平台地址");
            return ApiResult.ok(out);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            req.baseUrl().replaceAll("/+$", "") + "/api.php/v1/products"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Token", req.token() == null ? "" : req.token())
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            out.put("success", ok);
            out.put("message", ok ? "连接成功" : "禅道返回 HTTP " + resp.statusCode());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "连接失败:" + e.getMessage());
        }
        return ApiResult.ok(out);
    }

    @PostMapping("/projects/search")
    public ApiResult<List<Map<String, Object>>> searchProjects(@RequestBody BindingRequest req) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            req.baseUrl().replaceAll("/+$", "") + "/api.php/v1/products"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Token", req.token() == null ? "" : req.token())
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            var payload = mapper.readTree(resp.body());
            var array = payload.isArray() ? payload : payload.path("data");
            if (array.isArray()) {
                array.forEach(node -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("externalId", node.path("id").asText(""));
                    p.put("name", node.path("name").asText(""));
                    out.add(p);
                });
            }
        } catch (Exception ignored) {
            // 项目搜索失败返回空列表,由用户在连接测试中看到具体错误
        }
        return ApiResult.ok(out);
    }

    @PostMapping("/bindings")
    public ApiResult<Map<String, Object>> create(@RequestBody BindingRequest req) {
        long ws = RequestContext.workspaceId();
        String token = req.token() != null && !req.token().isBlank() ? req.token() : req.password();
        String config;
        try {
            config = mapper.writeValueAsString(Map.of(
                    "baseUrl", req.baseUrl() == null ? "" : req.baseUrl(),
                    "account", req.account() == null ? "" : req.account(),
                    "pollIntervalSeconds", req.pollIntervalSeconds() == null ? 60 : req.pollIntervalSeconds()));
        } catch (Exception e) {
            config = "{}";
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM ex_binding WHERE workspace_id=? AND provider='ZENTAO' AND project_id=? AND deleted_at IS NULL",
                ws, req.externalProjectId() == null ? "" : req.externalProjectId());
        long id;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO ex_binding (workspace_id, provider, project_id, name, credential_ref, config_json, status)"
                            + " VALUES (?, 'ZENTAO', ?, ?, ?, ?, 'ACTIVE')",
                    ws, req.externalProjectId() == null ? "1" : req.externalProjectId(),
                    req.externalProjectName() == null ? "禅道项目" : req.externalProjectName(),
                    token, config);
            id = jdbc.queryForObject("SELECT MAX(id) FROM ex_binding WHERE workspace_id=?", Long.class, ws);
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("UPDATE ex_binding SET name=?, credential_ref=?, config_json=?, status='ACTIVE' WHERE id=?",
                    req.externalProjectName() == null ? "禅道项目" : req.externalProjectName(), token, config, id);
        }
        return ApiResult.ok(bindingView(id));
    }

    @GetMapping("/bindings")
    public ApiResult<List<Map<String, Object>>> list() {
        long ws = RequestContext.workspaceId();
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM ex_binding WHERE workspace_id=? AND provider='ZENTAO' AND deleted_at IS NULL ORDER BY id DESC",
                Long.class, ws);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(bindingView(id));
        }
        return ApiResult.ok(out);
    }

    private Map<String, Object> bindingView(long id) {
        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT id, provider, project_id, name, credential_ref, config_json, status FROM ex_binding WHERE id=?", id);
        String baseUrl = "";
        try {
            var node = mapper.readTree(String.valueOf(raw.get("config_json")));
            baseUrl = node.path("baseUrl").asText("");
        } catch (Exception ignored) {
            // 旧绑定无 config_json 时留空
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("provider", raw.get("provider"));
        out.put("externalProjectId", String.valueOf(raw.get("project_id")));
        out.put("externalProjectName", raw.get("name"));
        out.put("baseUrl", baseUrl);
        out.put("clientKey", raw.get("credential_ref"));
        out.put("enabled", "ACTIVE".equals(raw.get("status")) ? 1 : 0);
        out.put("lastError", null);
        return out;
    }
}
