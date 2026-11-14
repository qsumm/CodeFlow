package com.ainative.v2.dash;

import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台与洞察:只读聚合。
 *
 * <p>全部指标直接由工单/运行/事件/用量表聚合而来,不引入额外的物化表 ——
 * 数据量在单工作区量级,实时聚合比维护双写一致更可靠。
 */
@Service
public class DashboardService {
    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    // ---------------------------------------------------------------- 工作台

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL"));
        out.put("today", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL AND DATE(gmt_create)=CURDATE()"));
        out.put("week", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL AND YEARWEEK(gmt_create,1)=YEARWEEK(CURDATE(),1)"));
        out.put("running", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='RUNNING'"));
        out.put("pending", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='PENDING'"));
        out.put("succeeded", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='SUCCEEDED'"));
        out.put("failed", count("SELECT COUNT(*) FROM rt_run WHERE workspace_id=? AND status='FAILED'"));
        out.put("agents", count("SELECT COUNT(*) FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL"));
        out.put("onlineExecutors", count("SELECT COUNT(*) FROM ag_executor WHERE workspace_id=? AND status='ONLINE' AND deleted_at IS NULL"));
        out.put("byState", jdbc.queryForList(
                "SELECT s.id AS stateId, s.name, s.category, COUNT(t.id) AS count FROM wi_flow_state s"
                        + " LEFT JOIN wi_ticket t ON t.state_id=s.id AND t.deleted_at IS NULL AND t.workspace_id=?"
                        + " WHERE s.workspace_id=? GROUP BY s.id, s.name, s.category ORDER BY s.sort_no",
                ws(), ws()));
        out.put("byType", jdbc.queryForList(
                "SELECT type, COUNT(*) AS count FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL GROUP BY type", ws()));
        return out;
    }

    public List<Map<String, Object>> running() {
        return jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.agent_id AS agentId, a.name AS agentName,"
                        + " r.status, r.attempt, r.started_at AS startedAt"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id LEFT JOIN ag_agent a ON a.id=r.agent_id"
                        + " WHERE r.workspace_id=? AND r.status IN ('RUNNING','PENDING','PAUSED','PAUSING') ORDER BY r.id DESC LIMIT 50",
                ws());
    }

    public Map<String, Object> realtime(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", jdbc.queryForList(
                "SELECT e.id, e.run_id AS dispatchId, e.event_type AS eventType, e.step_id AS stepId,"
                        + " e.detail_json AS detail, e.gmt_create AS createdAt FROM rt_event e"
                        + " WHERE e.workspace_id=? ORDER BY e.id DESC LIMIT " + n, ws()));
        out.put("dispatches", running());
        return out;
    }

    public List<Map<String, Object>> completed(String window) {
        String since = "week".equalsIgnoreCase(window)
                ? "YEARWEEK(r.ended_at,1)=YEARWEEK(CURDATE(),1)"
                : "DATE(r.ended_at)=CURDATE()";
        return jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.status, r.ended_at AS endedAt,"
                        + " TIMESTAMPDIFF(SECOND, r.started_at, r.ended_at) AS durationSec"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status IN ('SUCCEEDED','FAILED') AND r.ended_at IS NOT NULL AND " + since
                        + " ORDER BY r.ended_at DESC LIMIT 100", ws());
    }

    public List<Map<String, Object>> agentRunning(long agentId) {
        return jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.status, r.started_at AS startedAt"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.agent_id=? AND r.status IN ('RUNNING','PENDING') ORDER BY r.id DESC",
                ws(), agentId);
    }

    public Map<String, Object> pipelineHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stuck", jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.status, r.started_at AS startedAt,"
                        + " TIMESTAMPDIFF(HOUR, r.started_at, NOW()) AS hours"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status='RUNNING' AND r.started_at < NOW() - INTERVAL 24 HOUR"
                        + " ORDER BY r.started_at", ws()));
        out.put("noExecutor", jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.status, r.gmt_create AS createdAt"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id"
                        + " WHERE r.workspace_id=? AND r.status='PENDING' ORDER BY r.id DESC LIMIT 50", ws()));
        out.put("byState", overview().get("byState"));
        return out;
    }

    // ---------------------------------------------------------------- 洞察

    public Map<String, Object> deliveryOverview() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND deleted_at IS NULL");
        long done = count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='DONE'");
        long canceled = count("SELECT COUNT(*) FROM wi_ticket t JOIN wi_flow_state s ON s.id=t.state_id"
                + " WHERE t.workspace_id=? AND t.deleted_at IS NULL AND s.category='CANCELED'");
        out.put("total", total);
        out.put("done", done);
        out.put("canceled", canceled);
        out.put("inProgress", Math.max(0, total - done - canceled));
        out.put("completionRate", total == 0 ? 0 : Math.round(done * 1000.0 / total) / 10.0);
        out.put("created", jdbc.queryForList(
                "SELECT DATE(gmt_create) AS day, COUNT(*) AS count FROM wi_ticket"
                        + " WHERE workspace_id=? AND deleted_at IS NULL AND gmt_create >= CURDATE() - INTERVAL 30 DAY"
                        + " GROUP BY DATE(gmt_create) ORDER BY day", ws()));
        out.put("pending", jdbc.queryForList(
                "SELECT r.status, COUNT(*) AS count FROM rt_run r WHERE r.workspace_id=? GROUP BY r.status", ws()));
        return out;
    }

    public List<Map<String, Object>> stageDuration() {
        return jdbc.queryForList(
                "SELECT e.step_id AS stepId, COUNT(*) AS runs,"
                        + " ROUND(AVG(TIMESTAMPDIFF(SECOND, r.started_at, r.ended_at))) AS avgSec,"
                        + " MAX(TIMESTAMPDIFF(SECOND, r.started_at, r.ended_at)) AS maxSec"
                        + " FROM rt_event e JOIN rt_run r ON r.id=e.run_id"
                        + " WHERE e.workspace_id=? AND e.event_type='STEP_COMPLETED' AND r.ended_at IS NOT NULL"
                        + " GROUP BY e.step_id ORDER BY avgSec DESC", ws());
    }

    public List<Map<String, Object>> memberDelivery() {
        return jdbc.queryForList(
                "SELECT r.agent_id AS agentId, a.name AS agentName, COUNT(*) AS dispatches,"
                        + " SUM(CASE WHEN r.status='SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,"
                        + " SUM(CASE WHEN r.status='FAILED' THEN 1 ELSE 0 END) AS failed,"
                        + " ROUND(AVG(TIMESTAMPDIFF(SECOND, r.started_at, r.ended_at))) AS avgDurationSec"
                        + " FROM rt_run r LEFT JOIN ag_agent a ON a.id=r.agent_id"
                        + " WHERE r.workspace_id=? GROUP BY r.agent_id, a.name ORDER BY dispatches DESC", ws());
    }

    public List<Map<String, Object>> requirementDelivery() {
        return jdbc.queryForList(
                "SELECT t.id AS workitemId, t.title, t.priority, s.name AS stateName, s.category,"
                        + " t.gmt_create AS createdAt, t.gmt_modified AS updatedAt,"
                        + " (SELECT COUNT(*) FROM rt_run r WHERE r.ticket_id=t.id) AS dispatches"
                        + " FROM wi_ticket t LEFT JOIN wi_flow_state s ON s.id=t.state_id"
                        + " WHERE t.workspace_id=? AND t.deleted_at IS NULL ORDER BY t.gmt_modified DESC LIMIT 100", ws());
    }

    public List<Map<String, Object>> workers() {
        return jdbc.queryForList(
                "SELECT e.id AS executorId, e.name, e.agent_id AS agentId, a.name AS agentName, e.status,"
                        + " e.last_heartbeat AS lastHeartbeat,"
                        + " (SELECT COUNT(*) FROM rt_run r WHERE r.executor_id=e.id AND r.status='RUNNING') AS running"
                        + " FROM ag_executor e LEFT JOIN ag_agent a ON a.id=e.agent_id"
                        + " WHERE e.workspace_id=? AND e.deleted_at IS NULL ORDER BY e.id", ws());
    }

    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiUsage", jdbc.queryForList(
                "SELECT model, SUM(input_tokens) AS inputTokens, SUM(output_tokens) AS outputTokens, COUNT(*) AS calls"
                        + " FROM rt_ai_usage WHERE workspace_id=? GROUP BY model", ws()));
        out.put("artifacts", count("SELECT COUNT(*) FROM as_artifact WHERE workspace_id=? AND deleted_at IS NULL"));
        out.put("comments", count("SELECT COUNT(*) FROM wi_note WHERE workspace_id=? AND deleted_at IS NULL"));
        out.put("events", count("SELECT COUNT(*) FROM rt_event WHERE workspace_id=?"));
        return out;
    }

    public List<Map<String, Object>> auditInsights(int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        return jdbc.queryForList(
                "SELECT action, COUNT(*) AS count, MAX(gmt_create) AS lastAt FROM pf_audit"
                        + " WHERE workspace_id=? GROUP BY action ORDER BY count DESC LIMIT " + n, ws());
    }

    public Map<String, Object> humanAgentParticipation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("humanComments", count("SELECT COUNT(*) FROM wi_note WHERE workspace_id=? AND author_type='HUMAN' AND deleted_at IS NULL"));
        out.put("agentComments", count("SELECT COUNT(*) FROM wi_note WHERE workspace_id=? AND author_type='AGENT' AND deleted_at IS NULL"));
        out.put("humanAssignments", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND assignee_type='HUMAN' AND deleted_at IS NULL"));
        out.put("agentAssignments", count("SELECT COUNT(*) FROM wi_ticket WHERE workspace_id=? AND assignee_type='AGENT' AND deleted_at IS NULL"));
        out.put("slowest", jdbc.queryForList(
                "SELECT r.id AS dispatchId, r.ticket_id AS workitemId, t.title, r.agent_id AS agentId, a.name AS agentName,"
                        + " TIMESTAMPDIFF(SECOND, r.started_at, IFNULL(r.ended_at, NOW())) AS durationSec"
                        + " FROM rt_run r LEFT JOIN wi_ticket t ON t.id=r.ticket_id LEFT JOIN ag_agent a ON a.id=r.agent_id"
                        + " WHERE r.workspace_id=? ORDER BY durationSec DESC LIMIT 10", ws()));
        return out;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class, ws());
        return v == null ? 0 : v;
    }
}
