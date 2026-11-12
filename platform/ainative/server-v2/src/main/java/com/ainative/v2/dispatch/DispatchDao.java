package com.ainative.v2.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 交付调度域数据访问(rt_ 表):运行记录 / 事件 / 恢复控制 / 需求单 / 指引。 */
@Repository
public class DispatchDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Run(long id, long ticketId, Long agentId, Long executorId, Long sdlcId, int attempt,
                      String status, String error, LocalDateTime startedAt, LocalDateTime endedAt) {}

    public record Event(long id, long runId, int seq, String eventType, String stepId, String detailJson,
                        LocalDateTime ts) {}

    public record Request(long id, long ticketId, Long runId, String kind, String status,
                          String bodyJson, String answerJson, LocalDateTime gmtCreate) {}

    public record Guidance(long id, long ticketId, Long runId, String bodyMd, String status,
                           LocalDateTime gmtCreate) {}

    private final JdbcTemplate jdbc;

    public DispatchDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    private static LocalDateTime ldt(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Run mapRun(ResultSet rs, int i) throws SQLException {
        return new Run(rs.getLong("id"), rs.getLong("ticket_id"), nLong(rs, "agent_id"),
                nLong(rs, "executor_id"), nLong(rs, "sdlc_id"), rs.getInt("attempt"),
                rs.getString("status"), rs.getString("error"),
                ldt(rs, "started_at"), ldt(rs, "ended_at"));
    }

    private static final String RUN_SELECT = "id, ticket_id, agent_id, executor_id, sdlc_id, attempt, status, error, started_at, ended_at";

    public Optional<Run> findRun(long id) {
        List<Run> rows = jdbc.query("SELECT " + RUN_SELECT + " FROM rt_run WHERE id=?", DispatchDao::mapRun, id);
        return rows.stream().findFirst();
    }

    public List<Run> runsOfTicket(long ticketId) {
        return jdbc.query("SELECT " + RUN_SELECT + " FROM rt_run WHERE ticket_id=? ORDER BY id DESC", DispatchDao::mapRun, ticketId);
    }

    public List<Run> listRuns(String status, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (Math.max(1, pageNum) - 1) * size;
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT " + RUN_SELECT + " FROM rt_run ORDER BY id DESC LIMIT ? OFFSET ?",
                    DispatchDao::mapRun, size, offset);
        }
        return jdbc.query("SELECT " + RUN_SELECT + " FROM rt_run WHERE status=? ORDER BY id DESC LIMIT ? OFFSET ?",
                DispatchDao::mapRun, status, size, offset);
    }

    public long countRuns(String status) {
        Long n = (status == null || status.isBlank())
                ? jdbc.queryForObject("SELECT COUNT(*) FROM rt_run", Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM rt_run WHERE status=?", Long.class, status);
        return n == null ? 0 : n;
    }

    public long insertRun(long workspaceId, long ticketId, Long agentId, Long executorId, Long sdlcId, int attempt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rt_run (workspace_id, ticket_id, agent_id, executor_id, sdlc_id, attempt, status, started_at) VALUES (?,?,?,?,?,?, 'RUNNING', NOW())",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workspaceId);
            ps.setLong(2, ticketId);
            if (agentId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, agentId);
            if (executorId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, executorId);
            if (sdlcId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, sdlcId);
            ps.setInt(6, attempt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void finishRun(long id, String status, String error) {
        jdbc.update("UPDATE rt_run SET status=?, error=?, ended_at=NOW() WHERE id=? AND status IN ('RUNNING','PAUSED','PAUSING','PENDING')",
                status, error, id);
    }

    public void markStatus(long id, String status) {
        jdbc.update("UPDATE rt_run SET status=? WHERE id=?", status, id);
    }

    // ── 事件 ──
    public void appendEvent(long runId, int seq, String eventType, String stepId, String detailJson) {
        jdbc.update("INSERT INTO rt_event (workspace_id, run_id, seq, event_type, step_id, detail_json) VALUES (" + ws() + ",?,?,?,?,?)",
                runId, seq, eventType, stepId, detailJson);
    }

    public List<Event> eventsOf(long runId, int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        return jdbc.query("SELECT id, run_id, seq, event_type, step_id, detail_json, gmt_create FROM rt_event WHERE run_id=? ORDER BY seq LIMIT ?",
                (rs, i) -> new Event(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), ldt(rs, "gmt_create")), runId, n);
    }

    // ── 恢复控制 ──
    public void recordRecovery(long runId, String action, Long actorId, String detailJson) {
        jdbc.update("INSERT INTO rt_recovery (workspace_id, run_id, action, actor_id, detail_json) VALUES (" + ws() + ",?,?,?,?)",
                runId, action, actorId, detailJson);
    }

    // ── 需求单(执行中向人求助) ──
    public List<Request> requestsOfTicket(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, run_id, kind, status, body_json, answer_json, gmt_create FROM rt_request WHERE ticket_id=? ORDER BY id DESC",
                (rs, i) -> new Request(rs.getLong(1), rs.getLong(2), nLong(rs, "run_id"), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), ldt(rs, "gmt_create")), ticketId);
    }

    public Optional<Request> findRequest(long id) {
        List<Request> rows = jdbc.query("SELECT id, ticket_id, run_id, kind, status, body_json, answer_json, gmt_create FROM rt_request WHERE id=?",
                (rs, i) -> new Request(rs.getLong(1), rs.getLong(2), nLong(rs, "run_id"), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), ldt(rs, "gmt_create")), id);
        return rows.stream().findFirst();
    }

    public void replyRequest(long id, String answerJson) {
        jdbc.update("UPDATE rt_request SET status='ANSWERED', answer_json=? WHERE id=? AND status='PENDING'", answerJson, id);
    }

    // ── 指引 ──
    public void addGuidance(long ticketId, Long runId, String bodyMd) {
        jdbc.update("INSERT INTO rt_guidance (workspace_id, ticket_id, run_id, body_md, status) VALUES (" + ws() + ",?,?,?, 'OPEN')",
                ticketId, runId, bodyMd);
    }

    public List<Guidance> guidanceOf(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, run_id, body_md, status, gmt_create FROM rt_guidance WHERE ticket_id=? ORDER BY id DESC",
                (rs, i) -> new Guidance(rs.getLong(1), rs.getLong(2), nLong(rs, "run_id"), rs.getString(4),
                        rs.getString(5), ldt(rs, "gmt_create")), ticketId);
    }

    public List<Guidance> guidanceOfRun(long runId) {
        return jdbc.query("SELECT id, ticket_id, run_id, body_md, status, gmt_create FROM rt_guidance WHERE run_id=? ORDER BY id DESC",
                (rs, i) -> new Guidance(rs.getLong(1), rs.getLong(2), nLong(rs, "run_id"), rs.getString(4),
                        rs.getString(5), ldt(rs, "gmt_create")), runId);
    }
}
