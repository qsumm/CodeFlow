package com.ainative.v2.dash;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;


/** 工作台 + 洞察只读接口。 */
@RestController
public class DashboardController {
    private final DashboardService service;
    private final InsightsContractService contract;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DashboardController(DashboardService service, InsightsContractService contract,
                               org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.service = service;
        this.contract = contract;
        this.jdbc = jdbc;
    }

    // ---------- 工作台 ----------
    @GetMapping("/api/dashboard/overview")
    public ApiResult<Map<String, Object>> overview() {
        RequestContext.requireUserId();
        long ws = RequestContext.workspaceId();
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("todayCreatedWorkitems", countHelper("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND DATE(gmt_create)=CURDATE()"));
        kpi.put("runningWorkitems", countHelper("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category NOT IN ('DONE','CANCELED'))"));
        kpi.put("todayCompletedTasks", countHelper("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED' AND DATE(ended_at)=CURDATE()"));
        kpi.put("weekCompletedTasks", countHelper("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED' AND YEARWEEK(ended_at,1)=YEARWEEK(CURDATE(),1)"));
        Map<String, Object> byLifecycle = new LinkedHashMap<>();
        byLifecycle.put("init", countHelper("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category='NEW')"));
        byLifecycle.put("inProgress", kpi.get("runningWorkitems"));
        byLifecycle.put("done", countHelper("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'"));
        byLifecycle.put("canceled", countHelper("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='CANCELED'"));
        Map<String, Object> byType = new LinkedHashMap<>();
        byType.put("req", countHelper("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='REQ' AND deleted_at IS NULL"));
        byType.put("task", countHelper("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='TASK' AND deleted_at IS NULL"));
        byType.put("bug", countHelper("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='BUG' AND deleted_at IS NULL"));
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("byLifecycle", byLifecycle);
        inventory.put("byType", byType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", kpi);
        out.put("inventory", inventory);
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        return ApiResult.ok(out);
    }

    private long countHelper(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class, RequestContext.workspaceId());
        return v == null ? 0 : v;
    }

    @GetMapping("/api/dashboard/running")
    public ApiResult<List<Map<String, Object>>> running() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT t.id AS workitemId, t.title, a.name AS agentName,"
                        + " (SELECT step_id FROM rt_event e WHERE e.run_id=r.id ORDER BY id DESC LIMIT 1) AS stepName,"
                        + " TIMESTAMPDIFF(MINUTE, r.started_at, NOW()) AS runningMinutes,"
                        + " (SELECT COUNT(*) FROM rt_run x WHERE x.ticket_id=r.ticket_id AND x.status='RUNNING') AS runningDispatchCount"
                        + " FROM rt_run r LEFT JOIN ag_agent a ON a.id=r.agent_id LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status IN ('RUNNING','PENDING') ORDER BY r.id DESC LIMIT 50", RequestContext.workspaceId()));
    }

    @GetMapping("/api/dashboard/realtime")
    public ApiResult<Map<String, Object>> realtime(@RequestParam(defaultValue = "7") int range,
                                                   @RequestParam(required = false) Long agentId) {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.realtime(range, agentId));
    }

    @GetMapping("/api/dashboard/completed/today")
    public ApiResult<List<Map<String, Object>>> completedToday() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT t.id AS workitemId, t.title FROM rt_run r JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status='SUCCEEDED' AND DATE(r.ended_at)=CURDATE()"
                        + " ORDER BY r.ended_at DESC LIMIT 50", RequestContext.workspaceId()));
    }

    @GetMapping("/api/dashboard/completed/week")
    public ApiResult<List<Map<String, Object>>> completedWeek() {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT t.id AS workitemId, t.title FROM rt_run r JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status='SUCCEEDED' AND YEARWEEK(r.ended_at,1)=YEARWEEK(CURDATE(),1)"
                        + " ORDER BY r.ended_at DESC LIMIT 50", RequestContext.workspaceId()));
    }

    @GetMapping("/api/dashboard/pipeline-health")
    public ApiResult<Map<String, Object>> pipelineHealth(@RequestParam(defaultValue = "7d") String range) {
        RequestContext.requireUserId();
        long ws = RequestContext.workspaceId();
        long ok = jdbc.queryForObject("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED'", Long.class, ws);
        long failed = jdbc.queryForObject("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='FAILED'", Long.class, ws);
        long workitems = jdbc.queryForObject("SELECT COUNT(DISTINCT ticket_id) FROM rt_run WHERE workspace_id=?", Long.class, ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range);
        out.put("successRate", ok + failed == 0 ? null : Math.round(ok * 1000.0 / (ok + failed)) / 10.0);
        Map<String, Object> successSub = new LinkedHashMap<>();
        successSub.put("succeeded", ok);
        successSub.put("total", ok + failed);
        out.put("successSub", successSub);
        out.put("failedOrTimeout", failed);
        Map<String, Object> rework = new LinkedHashMap<>();
        rework.put("rate", null);
        rework.put("reworkedWorkitems", 0);
        rework.put("executedWorkitems", workitems);
        rework.put("retryCount", jdbc.queryForObject("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND attempt>1", Long.class, ws));
        out.put("rework", rework);
        out.put("avgWorkitemDurationMinutes", null);
        out.put("generatedAt", java.time.LocalDateTime.now().toString());
        return ApiResult.ok(out);
    }

    @GetMapping("/api/dashboard/agents/{id}/running")
    public ApiResult<List<Map<String, Object>>> agentRunning(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.agent_id AS agentId, a.name AS agentName,"
                        + " r.ticket_id AS workitemId, t.title AS workitemTitle,"
                        + " TIMESTAMPDIFF(MINUTE, r.started_at, NOW()) AS runningMinutes"
                        + " FROM rt_run r LEFT JOIN ag_agent a ON a.id=r.agent_id LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.agent_id=? AND r.status='RUNNING' ORDER BY r.id DESC LIMIT 20",
                RequestContext.workspaceId(), id));
    }

    // ---------- 洞察 ----------
    @GetMapping("/api/insights/delivery-overview")
    public ApiResult<Map<String, Object>> deliveryOverview() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.deliveryOverview());
    }

    @GetMapping("/api/insights/delivery-overview/created")
    public ApiResult<Object> createdTrend() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.deliveryOverview().get("created"));
    }

    @GetMapping("/api/insights/delivery-overview/pending")
    public ApiResult<Map<String, Object>> pendingWorkitems(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long uid = RequestContext.requireUserId();
        return ApiResult.ok(contract.pendingWorkitems(uid));
    }

    @GetMapping("/api/insights/stage-duration")
    public ApiResult<Map<String, Object>> stageDuration() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.stageDuration());
    }

    @GetMapping("/api/insights/member-delivery")
    public ApiResult<Map<String, Object>> memberDelivery() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.memberDelivery());
    }

    @GetMapping("/api/insights/requirement-delivery")
    public ApiResult<List<Map<String, Object>>> requirementDelivery() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.requirementDelivery());
    }

    @GetMapping("/api/insights/workers")
    public ApiResult<List<Map<String, Object>>> workers() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.workers());
    }

    @GetMapping("/api/insights/metrics")
    public ApiResult<Map<String, Object>> metrics() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.metrics());
    }

    @GetMapping("/api/insights/audit")
    public ApiResult<Map<String, Object>> audit(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.auditPage(page, size));
    }

    @GetMapping("/api/insights/human-agent-participation")
    public ApiResult<Map<String, Object>> participation() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.participation());
    }

    @GetMapping("/api/insights/human-agent-participation/refresh")
    public ApiResult<Map<String, Object>> refreshParticipation() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.participation());
    }

    @GetMapping("/api/insights/human-agent-participation/slowest")
    public ApiResult<Map<String, Object>> slowest() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.slowest());
    }

    // ---------- 空间级看板(前端 spaceDashboard/types.ts 契约) ----------
    @GetMapping("/api/space-dashboard/overview")
    public ApiResult<Map<String, Object>> spaceDashboard() {
        RequestContext.requireUserId();
        return ApiResult.ok(contract.spaceDashboard());
    }
}
