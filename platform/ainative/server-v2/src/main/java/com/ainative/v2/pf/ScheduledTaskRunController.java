package com.ainative.v2.pf;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务运行历史。
 *
 * <p>pf_task_run 只存 task_id 与 result_json(内含 ticketId/dispatchId),
 * 详情视图按需 join 出工单、派发、产物与评论,和 v1 的 run 视图字段对齐。
 */
@RestController
@RequestMapping("/api/scheduled-task-runs")
public class ScheduledTaskRunController {
    private final JdbcTemplate jdbc;

    public ScheduledTaskRunController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    @GetMapping
    public ApiResult<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE r.workspace_id=? AND r.deleted_at IS NULL");
        args.add(ws());
        if (taskId != null) {
            where.append(" AND r.task_id=?");
            args.add(taskId);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM pf_task_run r" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.task_id AS taskId, t.name AS taskName, r.status, r.started_at AS startedAt,"
                        + " r.ended_at AS endedAt, r.result_json AS result"
                        + " FROM pf_task_run r LEFT JOIN pf_task t ON t.id=r.task_id" + where
                        + " ORDER BY r.id DESC LIMIT " + limit + " OFFSET " + offset, pageArgs.toArray());
        return ApiResult.ok(new PageResult<>(rows, total == null ? 0 : total, page, limit));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.task_id AS taskId, t.name AS taskName, t.cron_expr AS cronExpr, r.status,"
                        + " r.started_at AS startedAt, r.ended_at AS endedAt, r.result_json AS result"
                        + " FROM pf_task_run r LEFT JOIN pf_task t ON t.id=r.task_id WHERE r.id=? AND r.workspace_id=?",
                id, ws());
        if (rows.isEmpty()) {
            throw new BizException("21002", "运行记录不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        long ticketId = linkedId(out, "ticketId");
        long dispatchId = linkedId(out, "dispatchId");
        out.put("ticketId", ticketId == 0 ? null : ticketId);
        out.put("dispatchId", dispatchId == 0 ? null : dispatchId);
        if (ticketId != 0) {
            List<Map<String, Object>> ticket = jdbc.queryForList(
                    "SELECT id, title, body_md AS contentMd, state_id AS stateId FROM wi_ticket WHERE id=?", ticketId);
            out.put("workitem", ticket.isEmpty() ? null : ticket.get(0));
        }
        if (dispatchId != 0) {
            List<Map<String, Object>> run = jdbc.queryForList(
                    "SELECT id, ticket_id AS workitemId, agent_id AS agentId, status, started_at AS startedAt,"
                            + " ended_at AS endedAt, error FROM rt_run WHERE id=?", dispatchId);
            out.put("dispatch", run.isEmpty() ? null : run.get(0));
        }
        return ApiResult.ok(out);
    }

    @GetMapping("/{id}/artifacts")
    public ApiResult<List<Map<String, Object>>> artifacts(@PathVariable long id) {
        RequestContext.requireUserId();
        long ticketId = ticketIdOf(id);
        if (ticketId == 0) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, run_id AS runId, name, kind, size_bytes AS sizeBytes, gmt_create AS createdAt"
                        + " FROM as_artifact WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id DESC", ticketId));
    }

    @GetMapping("/{id}/comments")
    public ApiResult<List<Map<String, Object>>> comments(@PathVariable long id) {
        RequestContext.requireUserId();
        long ticketId = ticketIdOf(id);
        if (ticketId == 0) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, author_type AS authorType, author_id AS authorId, body_md AS contentMd, gmt_create AS createdAt"
                        + " FROM wi_note WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id", ticketId));
    }

    @GetMapping("/{id}/events")
    public ApiResult<List<Map<String, Object>>> events(@PathVariable long id) {
        RequestContext.requireUserId();
        long dispatchId = dispatchIdOf(id);
        if (dispatchId == 0) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, event_type AS eventType, step_id AS stepId, detail_json AS detail, gmt_create AS createdAt"
                        + " FROM rt_event WHERE run_id=? ORDER BY id", dispatchId));
    }

    @GetMapping("/{id}/participants")
    public ApiResult<List<Map<String, Object>>> participants(@PathVariable long id) {
        RequestContext.requireUserId();
        long ticketId = ticketIdOf(id);
        if (ticketId == 0) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT DISTINCT n.author_type AS type, n.author_id AS id,"
                        + " COALESCE(a.name, u.nickname, u.username) AS name FROM wi_note n"
                        + " LEFT JOIN ag_agent a ON n.author_type='AGENT' AND a.id=n.author_id"
                        + " LEFT JOIN id_user u ON n.author_type='HUMAN' AND u.id=n.author_id"
                        + " WHERE n.ticket_id=? AND n.deleted_at IS NULL", ticketId));
    }

    @GetMapping("/{id}/derived-workitems")
    public ApiResult<List<Map<String, Object>>> derivedWorkitems(@PathVariable long id) {
        RequestContext.requireUserId();
        long ticketId = ticketIdOf(id);
        if (ticketId == 0) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, title, state_id AS stateId, gmt_create AS createdAt FROM wi_ticket"
                        + " WHERE id=? AND deleted_at IS NULL", ticketId));
    }

    @GetMapping("/{id}/delivery-progress")
    public ApiResult<Map<String, Object>> deliveryProgress(@PathVariable long id) {
        RequestContext.requireUserId();
        long dispatchId = dispatchIdOf(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchId", dispatchId);
        out.put("events", dispatchId == 0 ? List.of() : jdbc.queryForList(
                "SELECT id, event_type AS eventType, step_id AS stepId, detail_json AS detail, gmt_create AS createdAt"
                        + " FROM rt_event WHERE run_id=? ORDER BY id", dispatchId));
        return ApiResult.ok(out);
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<Map<String, Object>> cancel(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE pf_task_run SET status='CANCELED', ended_at=NOW() WHERE id=? AND workspace_id=?", id, ws());
        long dispatchId = dispatchIdOf(id);
        if (dispatchId != 0) {
            jdbc.update("UPDATE rt_run SET status='CANCELED', ended_at=NOW()"
                    + " WHERE id=? AND status IN ('RUNNING','PENDING','PAUSED','PAUSING')", dispatchId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "CANCELED");
        return ApiResult.ok(out);
    }

    @PostMapping("/{id}/pause")
    public ApiResult<Map<String, Object>> pause(@PathVariable long id) {
        RequestContext.requireUserId();
        jdbc.update("UPDATE pf_task_run SET status='PAUSED' WHERE id=? AND workspace_id=?", id, ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "PAUSED");
        return ApiResult.ok(out);
    }

    // ---------------------------------------------------------------- 关联解析

    private long ticketIdOf(long runId) {
        return linkedId(detailResult(runId), "ticketId");
    }

    private long dispatchIdOf(long runId) {
        return linkedId(detailResult(runId), "dispatchId");
    }

    private Map<String, Object> detailResult(long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT result_json FROM pf_task_run WHERE id=? AND workspace_id=?", runId, ws());
        if (rows.isEmpty() || rows.get(0).get("result_json") == null) {
            return Map.of();
        }
        Object raw = rows.get(0).get("result_json");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private long linkedId(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
