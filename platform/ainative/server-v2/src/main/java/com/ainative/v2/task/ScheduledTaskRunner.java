package com.ainative.v2.task;

import com.ainative.v2.dispatch.DispatchLaunchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务执行器:到点触发 → 建运行记录 → 复用派发链路(与人工指派同一条路径)。
 * 模板字段:{"agentId":10001,"sdlcId":2,"title":"...","contentMd":"..."}
 */
@Component
public class ScheduledTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRunner.class);

    private final JdbcTemplate jdbc;
    private final DispatchLaunchService dispatchLaunch;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScheduledTaskRunner(JdbcTemplate jdbc, DispatchLaunchService dispatchLaunch) {
        this.jdbc = jdbc;
        this.dispatchLaunch = dispatchLaunch;
    }

    /** 每 30 秒扫一次;同一分钟内每任务只触发一次(last_run_at 判重)。 */
    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        LocalDateTime now = dbNow();
        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT id, name, cron_expr, ticket_template_json FROM pf_task WHERE workspace_id=1 AND enabled=1 AND deleted_at IS NULL");
        for (Map<String, Object> t : tasks) {
            try {
                CronExpression cron = CronExpression.parse(String.valueOf(t.get("cron_expr")));
                if (!cron.matches(now)) {
                    continue;
                }
                long taskId = ((Number) t.get("id")).longValue();
                if (alreadyRan(taskId)) {
                    continue;
                }
                trigger(taskId, String.valueOf(t.get("name")), String.valueOf(t.get("ticket_template_json")));
            } catch (Exception e) {
                log.warn("scheduled task tick failed error={}", e.getMessage());
            }
        }
    }

    /**
     * 取数据库当前时间作为调度基准。
     * 调度判重与 cron 匹配必须和写库时间戳用同一个时钟 —— 应用容器与数据库
     * 容器时区不一致时,按 JVM 时间比较会把历史运行误判成"本分钟已跑"。
     */
    private LocalDateTime dbNow() {
        try {
            return jdbc.queryForObject("SELECT NOW()", LocalDateTime.class);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /** 同一分钟内是否已触发过(以数据库时钟的分钟边界为准)。 */
    /** 手动立即触发一次(供「立即执行」按钮),与调度 tick 共用同一条建单链路。 */
    public Map<String, Object> triggerNow(long taskId, String name, String templateJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        try {
            trigger(taskId, name, templateJson);
            Long runId = jdbc.queryForObject(
                    "SELECT MAX(id) FROM pf_task_run WHERE task_id=?", Long.class, taskId);
            out.put("runId", runId);
            out.put("status", "TRIGGERED");
        } catch (Exception e) {
            out.put("status", "FAILED");
            out.put("error", e.getMessage());
        }
        return out;
    }

    private boolean alreadyRan(long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND started_at >= DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:00')",
                Integer.class, taskId);
        return n != null && n > 0;
    }

    private void trigger(long taskId, String name, String templateJson) {
        JsonNode tpl;
        try {
            tpl = mapper.readTree(templateJson == null || templateJson.isBlank() ? "{}" : templateJson);
        } catch (Exception e) {
            log.warn("scheduled task template invalid taskId={} error={}", taskId, e.getMessage());
            return;
        }
        long agentId = tpl.path("agentId").asLong(0);
        if (agentId == 0) {
            log.warn("scheduled task skipped (no agentId) taskId={}", taskId);
            return;
        }
        Long sdlcId = tpl.hasNonNull("sdlcId") ? tpl.get("sdlcId").asLong() : null;
        String title = tpl.path("title").asText(name);
        String content = tpl.path("contentMd").asText("定时任务:" + name);

        jdbc.update("INSERT INTO pf_task_run (workspace_id, task_id, status, started_at) VALUES (1,?, 'RUNNING', NOW())", taskId);
        Long runId = jdbc.queryForObject("SELECT MAX(id) FROM pf_task_run WHERE task_id=?", Long.class, taskId);
        try {
            Long flowId = defaultFlowId();
            Long stateId = flowId == null ? null : firstStateId(flowId);
            jdbc.update("INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id, version) VALUES (1,?,?, 'REQ', 2,?,?, 0)",
                    title, content, flowId, stateId);
            long ticketId = idOfLastTicket();
            jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, to_val) VALUES (1,?, 'SCHEDULED_TRIGGER', 'SYSTEM', ?)",
                    ticketId, name);
            long dispatchId = dispatchLaunch.start(ticketId, agentId, sdlcId);
            jdbc.update("UPDATE pf_task_run SET status='DISPATCHED', result_json=? WHERE id=?",
                    "{\"ticketId\":" + ticketId + ",\"dispatchId\":" + dispatchId + "}", runId);
            log.info("scheduled task triggered taskId={} runId={} ticketId={} dispatchId={}",
                    taskId, runId, ticketId, dispatchId);
        } catch (Exception e) {
            jdbc.update("UPDATE pf_task_run SET status='FAILED', result_json=? WHERE id=?",
                    "{\"error\":\"" + e.getMessage().replace('"', '\'') + "\"}", runId);
            log.warn("scheduled task failed taskId={} error={}", taskId, e.getMessage());
        }
    }

    private Long defaultFlowId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=1 AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1",
                Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long firstStateId(long flowId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wi_flow_state WHERE flow_id=? AND category='NEW' ORDER BY sort_no LIMIT 1",
                Long.class, flowId);
        if (ids.isEmpty()) {
            ids = jdbc.queryForList("SELECT id FROM wi_flow_state WHERE flow_id=? ORDER BY sort_no LIMIT 1", Long.class, flowId);
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long idOfLastTicket() {
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM wi_ticket WHERE workspace_id=1", Long.class);
        return id == null ? 0 : id;
    }
}
