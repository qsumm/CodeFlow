package com.ainative.v2.misc;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证扩展(SSO 占位)与执行器运行轨迹扩展(activities/observations/turns 事件流)。
 * SSO 在 v2 的原则:未配置 IdP 时显式声明不可用,不伪造登录成功。
 */
@RestController
public class AuthTraceController {
    private final JdbcTemplate jdbc;
    private final com.ainative.v2.auth.JwtService jwt;

    public AuthTraceController(JdbcTemplate jdbc, com.ainative.v2.auth.JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    // ================================================================ SSO

    @GetMapping("/api/auth/sso/providers")
    public ApiResult<List<Map<String, Object>>> ssoProviders() {
        List<Map<String, Object>> idps = jdbc.queryForList(
                "SELECT id, name, status FROM id_idp WHERE deleted_at IS NULL AND status='ACTIVE'");
        for (Map<String, Object> idp : idps) {
            idp.put("startUrl", "/api/auth/sso/" + idp.get("id") + "/start");
        }
        return ApiResult.ok(idps);
    }

    @GetMapping("/api/auth/sso/session")
    public ApiResult<Map<String, Object>> ssoSession() {
        return ApiResult.ok(Map.of("authenticated", false, "provider", null));
    }

    @PostMapping("/api/auth/sso/bind")
    public ApiResult<Map<String, Object>> ssoBind(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.ok(Map.of("bound", false, "message", "未配置身份提供商,无法绑定联邦身份"));
    }

    @GetMapping("/api/auth/sso/{idpId}/start")
    public ApiResult<Map<String, Object>> ssoStart(@PathVariable long idpId) {
        List<Map<String, Object>> idps = jdbc.queryForList(
                "SELECT id, name FROM id_idp WHERE id=? AND deleted_at IS NULL AND status='ACTIVE'", idpId);
        if (idps.isEmpty()) {
            throw new BizException("10404", "身份提供商不存在或未启用");
        }
        return ApiResult.ok(Map.of("idpId", idpId, "redirectUrl", "", "message", "IdP 元数据未配置,无法发起跳转"));
    }

    @PostMapping("/api/auth/logout")
    public ApiResult<Void> logout() {
        RequestContext.requireUserId();
        // JWT 无状态:登出即客户端清 token;预留黑名单扩展点
        return ApiResult.ok(null);
    }

    /**
     * 刷新访问令牌。前端在访问令牌过期时用 refreshToken 换新令牌;
     * 请求体可为空(此时按登录态续签,兼容旧调用)。
     */
    @PostMapping("/api/auth/refresh")
    public ApiResult<Map<String, Object>> refresh(@RequestBody(required = false) Map<String, Object> body) {
        String refreshToken = body == null || body.get("refreshToken") == null
                ? null : String.valueOf(body.get("refreshToken"));
        Long workspaceId = body == null || body.get("workspaceId") == null
                ? null : Long.valueOf(String.valueOf(body.get("workspaceId")));
        com.ainative.v2.auth.JwtService.TokenClaims claims = refreshToken == null ? null : jwt.verifyRefresh(refreshToken);
        long uid;
        Long workspace;
        if (claims != null) {
            uid = claims.userId();
            workspace = workspaceId != null ? workspaceId : claims.workspaceId();
        } else {
            uid = RequestContext.requireUserId();
            workspace = workspaceId != null ? workspaceId : RequestContext.workspaceId();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", jwt.issue(uid, workspace));
        out.put("refreshToken", jwt.issueRefresh(uid, workspace));
        return ApiResult.ok(out);
    }

    // ================================================================ 运行轨迹扩展

    /** 聚合活动视图:按 step 分组的事件摘要,给前端活动时间线。 */
    @GetMapping("/api/dispatches/{id}/runtime-trace/activities")
    public ApiResult<List<Map<String, Object>>> activities(@PathVariable long id,
                                                           @RequestParam(defaultValue = "100") int limit) {
        RequestContext.requireUserId();
        int n = Math.max(1, Math.min(limit, 500));
        return ApiResult.ok(jdbc.queryForList(
                "SELECT step_id AS stepId, MIN(gmt_create) AS startedAt, MAX(gmt_create) AS endedAt,"
                        + " COUNT(*) AS events, SUBSTRING_INDEX(GROUP_CONCAT(event_type ORDER BY id), ',', 1) AS firstEvent"
                        + " FROM rt_event WHERE run_id=? GROUP BY step_id ORDER BY MIN(id) LIMIT " + n, id));
    }

    /** 观测事件(MCP 调用等):event_type 前缀过滤。 */
    @GetMapping("/api/dispatches/{id}/runtime-trace/observations/mcp")
    public ApiResult<List<Map<String, Object>>> mcpObservations(@PathVariable long id,
                                                                @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        String sql = "SELECT id, step_id AS stepId, event_type AS eventType, detail_json AS payload,"
                + " gmt_create AS createdAt FROM rt_event WHERE run_id=? AND (event_type LIKE 'MCP%' OR event_type LIKE 'OBS%')";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, id));
    }

    @GetMapping("/api/dispatches/{id}/runtime-trace/observations/{kind}")
    public ApiResult<List<Map<String, Object>>> observations(@PathVariable long id, @PathVariable String kind,
                                                             @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        String sql = "SELECT id, step_id AS stepId, event_type AS eventType, detail_json AS payload,"
                + " gmt_create AS createdAt FROM rt_event WHERE run_id=? AND event_type LIKE ?";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, id, kind.toUpperCase() + "%"));
    }

    /** 会话轮次轨迹:CONVERSATION_TURN 事件序列。 */
    @GetMapping("/api/dispatches/{id}/runtime-trace/turns")
    public ApiResult<List<Map<String, Object>>> turnTrace(@PathVariable long id,
                                                          @RequestParam(required = false) Long afterId) {
        RequestContext.requireUserId();
        String sql = "SELECT id, step_id AS stepId, event_type AS eventType, detail_json AS payload,"
                + " gmt_create AS createdAt FROM rt_event WHERE run_id=? AND event_type LIKE 'CONVERSATION%'";
        if (afterId != null) {
            sql += " AND id>" + afterId;
        }
        sql += " ORDER BY id LIMIT 200";
        return ApiResult.ok(jdbc.queryForList(sql, id));
    }

    @GetMapping("/api/dispatches/{id}/runtime-trace/context")
    public ApiResult<Map<String, Object>> traceContext(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id, ticket_id AS workitemId, agent_id AS agentId, executor_id AS executorId,"
                        + " status, attempt, started_at AS startedAt, ended_at AS endedAt FROM rt_run WHERE id=?", id);
        if (runs.isEmpty()) {
            throw new BizException("14004", "派发不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(runs.get(0));
        out.put("eventCount", jdbc.queryForObject("SELECT COUNT(*) FROM rt_event WHERE run_id=?", Long.class, id));
        out.put("artifactCount", jdbc.queryForObject("SELECT COUNT(*) FROM as_artifact WHERE run_id=? AND deleted_at IS NULL",
                Long.class, id));
        return ApiResult.ok(out);
    }

    /** 实时活动摘要:最近事件 + 运行状态,给派发详情页的 live 面板。 */
    @GetMapping("/api/dispatches/{id}/live-activity")
    public ApiResult<Map<String, Object>> liveActivity(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id, status, started_at AS startedAt, ended_at AS endedAt FROM rt_run WHERE id=?", id);
        if (runs.isEmpty()) {
            throw new BizException("14004", "派发不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(runs.get(0));
        out.put("recentEvents", jdbc.queryForList(
                "SELECT id, event_type AS eventType, step_id AS stepId, detail_json AS payload, gmt_create AS createdAt"
                        + " FROM rt_event WHERE run_id=? ORDER BY id DESC LIMIT 20", id));
        out.put("online", jdbc.queryForList(
                "SELECT 1 FROM ag_executor e JOIN rt_run r ON r.executor_id=e.id"
                        + " WHERE r.id=? AND e.status='ONLINE'", id).size() > 0);
        return ApiResult.ok(out);
    }
}
