package com.ainative.v2.dash;

import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效能分析与交付总览:按前端 TS 类型契约逐字段对齐(空态安全)。
 *
 * <p>前端类型定义见 insights/types.ts、overview/types.ts、spaceDashboard/types.ts。
 * 本服务的所有响应保证字段齐全 —— 前端大量直接取值(如 data.created.count),
 * 缺字段会直接 TypeError 白屏。趋势数组统一 7 点(近 7 天,无数据为 0)。
 */
@Service
public class InsightsContractService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbc;

    public InsightsContractService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    /** 近 7 天(含今天)的日期序列,SQL 聚合补零后按日对齐。 */
    private List<Map<String, Object>> daily7(String sql, Object... args) {
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Object d = row.get("d");
            Object c = row.get("c");
            if (d != null && c instanceof Number n) {
                byDay.put(String.valueOf(d), n.longValue());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            String day = today.minusDays(i).format(DAY);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("count", byDay.getOrDefault(day, 0L));
            out.add(point);
        }
        return out;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private Map<String, Object> period(LocalDate start, LocalDate end, long c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startDate", start.format(DAY));
        m.put("endDate", end.format(DAY));
        m.put("count", c);
        return m;
    }

    // ================================================================ 项目总览 /api/insights/delivery-overview

    public Map<String, Object> deliveryOverview() {
        LocalDate start = LocalDate.now().minusDays(6);
        LocalDate end = LocalDate.now();
        long created = count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL AND DATE(gmt_create)>=?", ws(), start);
        long done = count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'", ws());
        long inProgress = count("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category NOT IN ('DONE','CANCELED'))", ws());
        long failed = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='FAILED' AND ended_at>=?", ws(), start.atStartOfDay());
        long dispatched = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND ended_at>=?", ws(), start.atStartOfDay());
        long tokens = count("SELECT COALESCE(SUM(COALESCE(tokens_in,0)+COALESCE(tokens_out,0)),0) FROM cv_usage WHERE workspace_id=?", ws());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", start.format(DAY));
        out.put("endDate", end.format(DAY));
        out.put("timezone", "Asia/Shanghai");
        out.put("dataThrough", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        out.put("daysInRange", 7);
        Map<String, Object> created_ = new LinkedHashMap<>();
        created_.put("count", created);
        created_.put("daily", daily7("SELECT DATE(gmt_create) AS d, COUNT(*) AS c FROM wi_ticket"
                + " WHERE workspace_id=? AND deleted_at IS NULL AND gmt_create>=? GROUP BY DATE(gmt_create)", ws(), start.atStartOfDay()));
        out.put("created", created_);

        Map<String, Object> delivered = new LinkedHashMap<>();
        delivered.put("count", done);
        delivered.put("dailyAverage", Math.round(done / 7.0 * 10) / 10.0);
        delivered.put("previous", period(start.minusDays(7), start.minusDays(1), 0));
        delivered.put("changePercent", null);
        out.put("delivered", delivered);

        out.put("leadTimeDays", null);
        out.put("leadTimeChangePercent", null);
        out.put("inProgressCount", inProgress);
        out.put("firstPassRate", null);

        Map<String, Object> failed_ = new LinkedHashMap<>();
        failed_.put("count", failed);
        failed_.put("sharePercent", dispatched == 0 ? 0.0 : Math.round(failed * 1000.0 / dispatched) / 10.0);
        failed_.put("previous", period(start.minusDays(7), start.minusDays(1), 0));
        failed_.put("changePercent", null);
        out.put("failedDispatches", failed_);

        out.put("reworkRate", null);
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        tokenUsage.put("count", tokens);
        tokenUsage.put("daily", daily7("SELECT DATE(gmt_create) AS d, SUM(COALESCE(tokens_in,0)+COALESCE(tokens_out,0)) AS c FROM cv_usage"
                + " WHERE workspace_id=? AND gmt_create>=? GROUP BY DATE(gmt_create)", ws(), start.atStartOfDay()));
        tokenUsage.put("previous", period(start.minusDays(7), start.minusDays(1), 0));
        tokenUsage.put("changePercent", null);
        out.put("tokenUsage", tokenUsage);
        out.put("failureReasons", List.of());
        return out;
    }

    /** 总览页第二块:当前用户待处理工单(PendingWorkitemPage 契约)。 */
    public Map<String, Object> pendingWorkitems(long userId) {
        long uid = RequestContext.requireUserId();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT t.id, t.title, s.name AS statusName, s.category AS statusCategory, t.priority,"
                        + " t.gmt_modified AS gmtModified,"
                        + " CASE WHEN t.assignee_type='AGENT' THEN 'IN_PROGRESS' ELSE 'AWAITING_DECISION' END AS reason"
                        + " FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                        + " WHERE t.workspace_id=? AND t.deleted_at IS NULL"
                        + " AND (t.assignee_id=? OR COALESCE((SELECT actor_id FROM wi_journal j"
                        + "   WHERE j.ticket_id=t.id AND j.event_type='CREATE' ORDER BY j.id LIMIT 1),0)=?)"
                        + " AND (s.category IS NULL OR s.category NOT IN ('DONE','CANCELED'))"
                        + " ORDER BY t.gmt_modified DESC LIMIT 20", ws(), uid, uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", list.size());
        out.put("pageNum", 1);
        out.put("pageSize", 20);
        return out;
    }

    // ================================================================ 效能分析 /api/insights/*

    public Map<String, Object> metrics() {
        long totalTasks = count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL", ws());
        long completed = count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'", ws());
        long ok = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED'", ws());
        long failed = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='FAILED'", ws());
        long retry = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND attempt>1", ws());
        long tokens = count("SELECT COALESCE(SUM(COALESCE(tokens_in,0)+COALESCE(tokens_out,0)),0) FROM cv_usage WHERE workspace_id=?", ws());

        List<Map<String, Object>> createdDaily = daily7("SELECT DATE(gmt_create) AS d, COUNT(*) AS c FROM wi_ticket"
                + " WHERE workspace_id=? AND deleted_at IS NULL AND gmt_create>=? GROUP BY DATE(gmt_create)", ws(), LocalDate.now().minusDays(6).atStartOfDay());
        List<Object> trend = createdDaily.stream().map(x -> x.get("count")).toList();

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("totalTokens", tokens);
        cost.put("avgTokensPerTask", completed == 0 ? 0 : Math.round(tokens * 1.0 / completed));
        cost.put("dailyAvg", Math.round(tokens / 7.0));
        cost.put("trend", trend);
        cost.put("totalCredits", tokens);
        cost.put("avgCreditsPerTask", completed == 0 ? 0 : Math.round(tokens * 1.0 / completed));
        cost.put("dailyAvgCredits", Math.round(tokens / 7.0));
        cost.put("creditsTrend", trend);
        cost.put("byWorker", List.of());

        Map<String, Object> efficiency = new LinkedHashMap<>();
        efficiency.put("completionRate", totalTasks == 0 ? 0 : Math.round(completed * 1000.0 / totalTasks) / 10.0);
        efficiency.put("totalTasks", totalTasks);
        efficiency.put("completedTasks", completed);
        efficiency.put("avgDurationMinutes", 0);
        efficiency.put("trend", trend);

        Map<String, Object> stability = new LinkedHashMap<>();
        stability.put("successRate", ok + failed == 0 ? 0 : Math.round(ok * 1000.0 / (ok + failed)) / 10.0);
        stability.put("retryCount", retry);
        stability.put("blockedCount", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='PAUSED'", ws()));
        stability.put("trend", trend);

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("highRiskOps", 0);
        security.put("complianceRate", 100);
        security.put("auditBlocks", 0);
        security.put("trend", trend);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cost", cost);
        out.put("efficiency", efficiency);
        out.put("stability", stability);
        out.put("security", security);
        return out;
    }

    public Map<String, Object> auditPage(int page, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT DATE_FORMAT(a.gmt_create, '%Y-%m-%dT%H:%i:%s') AS timestamp,"
                        + " COALESCE(u.nickname, u.username, '系统') AS worker,"
                        + " a.actor_id AS actorId, a.action AS eventType,"
                        + " LEFT(COALESCE(a.detail_json,''),160) AS detail,"
                        + " CASE WHEN a.action LIKE '%delete%' THEN 'high' ELSE 'low' END AS riskLevel"
                        + " FROM pf_audit a LEFT JOIN id_user u ON u.id=a.actor_id"
                        + " WHERE a.workspace_id=? ORDER BY a.id DESC LIMIT " + limit + " OFFSET " + offset, ws());
        long total = count("SELECT COUNT(*) FROM pf_audit WHERE workspace_id=?", ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        return out;
    }

    public List<Map<String, Object>> workers() {
        return jdbc.queryForList(
                "SELECT a.id, a.name FROM ag_agent a WHERE a.workspace_id=? AND a.deleted_at IS NULL ORDER BY a.id", ws());
    }

    public Map<String, Object> stageDuration() {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        out.put("startDate", today.minusDays(6).format(DAY));
        out.put("endDate", today.format(DAY));
        out.put("timezone", "Asia/Shanghai");
        out.put("calculatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        out.put("executionCount", 0);
        out.put("workitemCount", 0);
        out.put("measuredExecutionCount", 0);
        out.put("missingDurationCount", 0);
        out.put("totalDurationMs", 0);
        out.put("stages", List.of());
        return out;
    }

    public Map<String, Object> participation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("medianTotalSeconds", null);
        out.put("agentWorkitemCount", 0);
        out.put("identifiedCompletedCount", 0);
        out.put("inferredAssignmentCount", 0);
        out.put("exclusions", Map.of());
        out.put("available", false);
        out.put("generatedAt", null);
        out.put("dataThrough", null);
        out.put("realtimeTail", false);
        out.put("averageTotalMinutes", null);
        out.put("refreshTriggered", false);
        out.put("sampleSize", 0);
        out.put("average", null);
        out.put("p90", null);
        return out;
    }

    public Map<String, Object> slowest() {
        return Map.of("items", List.of());
    }

    public Map<String, Object> memberDelivery() {
        LocalDate today = LocalDate.now();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("created", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL", ws()));
        summary.put("total", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL", ws()));
        summary.put("completed", count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'", ws()));
        summary.put("inProgress", count("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category='IN_PROGRESS')", ws()));
        summary.put("notStarted", 0);
        summary.put("pendingDecision", 0);
        summary.put("requirements", 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startDate", today.minusDays(6).format(DAY));
        out.put("endDate", today.format(DAY));
        out.put("timezone", "Asia/Shanghai");
        out.put("summary", summary);
        out.put("members", List.of());
        return out;
    }

    // ================================================================ 空间级看板 /api/space-dashboard/overview

    public Map<String, Object> spaceDashboard() {
        long uid = RequestContext.requireUserId();
        boolean admin = count("SELECT COUNT(*) FROM id_user WHERE id=? AND is_admin=1 AND deleted_at IS NULL", uid) > 0;
        List<Map<String, Object>> workspaces = jdbc.queryForList(
                "SELECT id, name FROM id_org WHERE deleted_at IS NULL ORDER BY id");
        LocalDate today = LocalDate.now();
        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("workspaceCount", workspaces.size());
        agents.put("agentCount", count("SELECT COUNT(*) FROM ag_agent WHERE deleted_at IS NULL"));
        agents.put("humanCount", count("SELECT COUNT(*) FROM id_user WHERE deleted_at IS NULL"));
        agents.put("squadCount", count("SELECT COUNT(*) FROM sq_squad WHERE deleted_at IS NULL"));

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start", today.minusDays(6).format(DAY));
        window.put("end", today.format(DAY));
        Map<String, Object> deliveryWindow = new LinkedHashMap<>(window);
        deliveryWindow.put("workTypes", List.of("REQ", "BUG", "TASK"));

        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("window", deliveryWindow);
        delivery.put("workitemCount", count("SELECT COUNT(*) FROM wi_ticket WHERE deleted_at IS NULL"));
        delivery.put("completedCount", count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.deleted_at IS NULL AND s.category='DONE'"));
        delivery.put("inProgressCount", count("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.deleted_at IS NULL AND (s.category IS NULL OR s.category NOT IN ('DONE','CANCELED'))"));
        delivery.put("topCompleted", List.of());
        delivery.put("topCompletedTotal", 0);
        delivery.put("bottomCompleted", List.of());
        delivery.put("bottomCompletedTotal", 0);

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("window", window);
        cost.put("totalCredits", 0);
        cost.put("dailyAverage", 0);
        cost.put("completedWorkitemCount", 0);
        cost.put("perCompletedWorkitem", null);
        cost.put("topCredits", List.of());
        cost.put("topCreditsTotal", 0);
        cost.put("bottomCredits", List.of());
        cost.put("bottomCreditsTotal", 0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformAdmin", admin);
        out.put("workspaceCount", workspaces.size());
        out.put("workspaces", workspaces);
        out.put("agents", agents);
        out.put("delivery", delivery);
        out.put("cost", cost);
        return out;
    }

    // ================================================================ 实时看板 /api/dashboard/realtime

    /**
     * RealtimeDashboard 契约:kpi/inventory/squads/workstations/health/runningFeed/recentFeed。
     * 前端默认 tab 就是它,任何缺字段都会 TypeError(实测 reading 'length')。
     */
    public Map<String, Object> realtime(int rangeDays, Long agentId) {
        long ws = ws();
        Map<String, Object> kpi = new LinkedHashMap<>();
        long runningDispatches = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='RUNNING'", ws);
        long inProgressWorkitems = count("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category NOT IN ('DONE','CANCELED'))", ws);
        long todayCompleted = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED' AND DATE(ended_at)=CURDATE()", ws);
        long weekCompleted = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED' AND YEARWEEK(ended_at,1)=YEARWEEK(CURDATE(),1)", ws);
        kpi.put("runningDispatches", runningDispatches);
        kpi.put("todayCompletedTasks", todayCompleted);
        kpi.put("weekCompletedTasks", weekCompleted);
        kpi.put("avgTaskDurationMinutes", 0);
        kpi.put("inProgressWorkitems", inProgressWorkitems);
        kpi.put("queuedDispatches", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='PENDING'", ws));
        kpi.put("activeSquads", count("SELECT COUNT(*) FROM sq_squad WHERE workspace_id=? AND deleted_at IS NULL", ws));
        kpi.put("onlineAgents", count("SELECT COUNT(*) FROM ag_executor WHERE workspace_id=? AND status='ONLINE' AND deleted_at IS NULL", ws));
        kpi.put("avgLoad", 0);
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("range", 7);
        cost.put("agentId", "all");
        cost.put("total", 0);
        cost.put("perTask", 0);
        cost.put("perDay", 0);
        cost.put("tokens", 0);
        kpi.put("cost", cost);

        Map<String, Object> byLifecycle = new LinkedHashMap<>();
        byLifecycle.put("init", count("SELECT COUNT(*) FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND (s.category IS NULL OR s.category='NEW')", ws));
        byLifecycle.put("inProgress", inProgressWorkitems);
        byLifecycle.put("done", count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'", ws));
        byLifecycle.put("canceled", count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='CANCELED'", ws));
        Map<String, Object> byType = new LinkedHashMap<>();
        byType.put("req", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='REQ' AND deleted_at IS NULL", ws));
        byType.put("task", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='TASK' AND deleted_at IS NULL", ws));
        byType.put("bug", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND type='BUG' AND deleted_at IS NULL", ws));
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("byLifecycle", byLifecycle);
        inventory.put("byType", byType);

        List<Map<String, Object>> squads = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT s.id, s.name, COUNT(m.agent_id) AS members FROM sq_squad s"
                        + " LEFT JOIN sq_member m ON m.squad_id=s.id AND m.deleted_at IS NULL"
                        + " WHERE s.workspace_id=? AND s.deleted_at IS NULL GROUP BY s.id, s.name ORDER BY s.id", ws)) {
            Map<String, Object> line = new LinkedHashMap<>();
            long squadId = row.get("id") instanceof Number n ? n.longValue() : 0;
            line.put("squadId", squadId);
            line.put("name", row.get("name"));
            long members = row.get("members") instanceof Number n ? n.longValue() : 0;
            line.put("members", members);
            long online = count("SELECT COUNT(DISTINCT e.agent_id) FROM sq_member m JOIN ag_executor e"
                    + " ON e.agent_id=m.agent_id AND e.status='ONLINE' AND e.deleted_at IS NULL"
                    + " WHERE m.squad_id=? AND m.deleted_at IS NULL", squadId);
            line.put("online", online);
            line.put("busy", 0);
            line.put("runningTasks", 0);
            line.put("inProgressWorkitems", 0);
            line.put("load", members == 0 ? 0 : Math.round(online * 100.0 / members) / 100.0);
            squads.add(line);
        }

        List<Map<String, Object>> workstations = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, agent_id, name, status FROM ag_executor"
                        + " WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id", ws)) {
            Map<String, Object> w = new LinkedHashMap<>();
            long execId = row.get("id") instanceof Number n ? n.longValue() : 0;
            w.put("agentId", row.get("agent_id"));
            w.put("name", row.get("name"));
            w.put("avatarUrl", null);
            w.put("runningTasks", 0);
            w.put("busy", "RUNNING".equalsIgnoreCase(String.valueOf(row.get("status"))));
            workstations.add(w);
        }

        Map<String, Object> health = new LinkedHashMap<>();
        long ok = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED'", ws);
        long failed = count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='FAILED'", ws);
        health.put("successRate", ok + failed == 0 ? null : Math.round(ok * 1000.0 / (ok + failed)) / 10.0);
        health.put("failedOrTimeout", failed);
        health.put("retries", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND attempt>1", ws));
        health.put("avgDurationMinutes", 0);

        List<Map<String, Object>> runningFeed = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT r.id, r.agent_id, a.name AS agentName, r.ticket_id, t.title, r.started_at"
                        + " FROM rt_run r LEFT JOIN ag_agent a ON a.id=r.agent_id LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status='RUNNING' ORDER BY r.id DESC LIMIT 20", ws)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dispatchId", row.get("id"));
            f.put("agentId", row.get("agent_id"));
            f.put("agentName", row.get("agentName"));
            f.put("workitemId", row.get("ticket_id"));
            f.put("workitemTitle", row.get("title"));
            f.put("stepName", null);
            f.put("runningMinutes", 0);
            runningFeed.add(f);
        }

        List<Map<String, Object>> recentFeed = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT r.id, a.name AS agentName, t.title, r.status, r.ended_at,"
                        + " TIMESTAMPDIFF(SECOND, r.started_at, r.ended_at)/60 AS mins"
                        + " FROM rt_run r LEFT JOIN ag_agent a ON a.id=r.agent_id LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status IN ('SUCCEEDED','FAILED') AND r.ended_at IS NOT NULL"
                        + " ORDER BY r.ended_at DESC LIMIT 20", ws)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dispatchId", row.get("id"));
            f.put("agentName", row.get("agentName"));
            f.put("workitemTitle", row.get("title"));
            String st = String.valueOf(row.get("status"));
            f.put("status", "FAILED".equals(st) ? "FAILED" : "SUCCEEDED");
            f.put("durationMinutes", row.get("mins") instanceof Number n ? n.longValue() : 0);
            f.put("finishedAt", String.valueOf(row.get("ended_at")));
            recentFeed.add(f);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", kpi);
        out.put("costComposition", null);
        out.put("inventory", inventory);
        out.put("squads", squads);
        out.put("workstations", workstations);
        out.put("health", health);
        out.put("runningFeed", runningFeed);
        out.put("recentFeed", recentFeed);
        out.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return out;
    }
}