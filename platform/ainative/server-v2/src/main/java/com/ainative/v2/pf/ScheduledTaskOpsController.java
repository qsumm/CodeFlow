package com.ainative.v2.pf;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务运维:立即执行 / 健康 / 运行历史(挂在 /api/scheduled-tasks/{id} 下)。
 * 文档管理(md_ 文档引用)与多轮工单派生在文档域落地后接入。
 */
@RestController
public class ScheduledTaskOpsController {
    private final JdbcTemplate jdbc;
    private final com.ainative.v2.task.ScheduledTaskRunner runner;

    public ScheduledTaskOpsController(JdbcTemplate jdbc, com.ainative.v2.task.ScheduledTaskRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private void require(long id) {
        if (jdbc.queryForList("SELECT 1 FROM pf_task WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                id, ws()).isEmpty()) {
            throw new BizException("21001", "定时任务不存在");
        }
    }

    /** 立即触发一次(无视 cron),复用与调度器完全相同的建单派发链路。 */
    @PostMapping("/api/scheduled-tasks/{id}/run-now")
    public ApiResult<Map<String, Object>> runNow(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, ticket_template_json FROM pf_task WHERE id=?", id);
        String name = String.valueOf(rows.get(0).get("name"));
        Object template = rows.get(0).get("ticket_template_json");
        Map<String, Object> out = runner.triggerNow(id, name,
                template == null ? "{}" : String.valueOf(template));
        return ApiResult.ok(out);
    }

    @GetMapping("/api/scheduled-tasks/{id}/runs")
    public ApiResult<List<Map<String, Object>>> runs(@PathVariable long id,
                                                     @RequestParam(defaultValue = "20") int limit) {
        RequestContext.requireUserId();
        require(id);
        int n = Math.max(1, Math.min(limit, 100));
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, status, started_at AS startedAt, ended_at AS endedAt, result_json AS result"
                        + " FROM pf_task_run WHERE task_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT " + n, id));
    }

    @GetMapping("/api/scheduled-tasks/{id}/health")
    public ApiResult<Map<String, Object>> health(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        Long runs24 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND started_at >= NOW() - INTERVAL 24 HOUR",
                Long.class, id);
        Long failed24 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND status='FAILED' AND started_at >= NOW() - INTERVAL 24 HOUR",
                Long.class, id);
        Map<String, Object> latest = new LinkedHashMap<>();
        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT id, status, started_at AS startedAt, ended_at AS endedAt FROM pf_task_run"
                        + " WHERE task_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", id);
        if (!last.isEmpty()) {
            latest.putAll(last.get(0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", id);
        out.put("runs24h", runs24 == null ? 0 : runs24);
        out.put("failed24h", failed24 == null ? 0 : failed24);
        out.put("health", failed24 != null && failed24 > 0 ? "DEGRADED" : "OK");
        out.put("latest", latest);
        return ApiResult.ok(out);
    }
}
