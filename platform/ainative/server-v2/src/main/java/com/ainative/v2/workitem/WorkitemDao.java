package com.ainative.v2.workitem;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 工作项域数据访问(wi_ 表). */
@Repository
public class WorkitemDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Ticket(long id, String title, String bodyMd, String type, int priority,
                         Long flowId, Long stateId, String assigneeType, Long assigneeId,
                         Long squadId, Long sdlcId, String externalSource, String externalId,
                         int version, LocalDateTime gmtCreate, LocalDateTime gmtModified,
                         LocalDateTime deletedAt, long creatorId) {}

    public record State(long id, long flowId, String name, String category, int sortNo) {}

    public record Edge(long fromStateId, long toStateId) {}

    public record Note(long id, long ticketId, String authorType, long authorId, String bodyMd,
                       LocalDateTime gmtCreate) {}

    public record Journal(long id, long ticketId, String eventType, String actorType, Long actorId,
                          String fromVal, String toVal, LocalDateTime gmtCreate) {}

    public record CommentCount(long ticketId, long count) {}

    private final JdbcTemplate jdbc;

    public WorkitemDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 统一 SELECT:创建者从 CREATE 事件回取(wi_ticket 不存 creator_id)。 */
    private static final String TICKET_SELECT =
            "t.id, t.title, t.body_md, t.type, t.priority, t.flow_id, t.state_id, t.assignee_type, t.assignee_id, "
            + "t.squad_id, t.sdlc_id, t.external_source, t.external_id, t.version, t.gmt_create, t.gmt_modified, "
            + "t.deleted_at, coalesce((SELECT actor_id FROM wi_journal j WHERE j.ticket_id=t.id AND j.event_type='CREATE' ORDER BY j.id LIMIT 1),0) AS creator_id";

    private static Ticket mapTicket(ResultSet rs, int i) throws SQLException {
        return new Ticket(rs.getLong("id"), rs.getString("title"), rs.getString("body_md"),
                rs.getString("type"), rs.getInt("priority"),
                nLong(rs, "flow_id"), nLong(rs, "state_id"),
                rs.getString("assignee_type"), nLong(rs, "assignee_id"),
                nLong(rs, "squad_id"), nLong(rs, "sdlc_id"),
                rs.getString("external_source"), rs.getString("external_id"),
                rs.getInt("version"), toLdt(rs.getTimestamp("gmt_create")),
                toLdt(rs.getTimestamp("gmt_modified")), toLdt(rs.getTimestamp("deleted_at")),
                rs.getLong("creator_id"));
    }

    /** seekdb/驱动可能把 BIGINT UNSIGNED 返回为 BigInteger,统一按 Number 取。 */
    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public Optional<Ticket> findById(long id, boolean includeDeleted) {
        String sql = "SELECT " + TICKET_SELECT + " FROM wi_ticket t WHERE t.id = ? AND t.workspace_id=" + ws() + ""
                + (includeDeleted ? "" : " AND t.deleted_at IS NULL");
        List<Ticket> rows = jdbc.query(sql, WorkitemDao::mapTicket, id);
        return rows.stream().findFirst();
    }

    public long insert(long workspaceId, String title, String bodyMd, String type, int priority,
                       Long flowId, Long stateId, Long creatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id, version) VALUES (?,?,?,?,?,?,?,0)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workspaceId);
            ps.setString(2, title);
            ps.setString(3, bodyMd);
            ps.setString(4, type);
            ps.setInt(5, priority);
            if (flowId == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, flowId);
            if (stateId == null) ps.setNull(7, java.sql.Types.BIGINT); else ps.setLong(7, stateId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void updateContent(long id, String title, String bodyMd, int expectedVersion) {
        int n = jdbc.update("UPDATE wi_ticket SET title=?, body_md=?, version=version+1 WHERE id=? AND deleted_at IS NULL AND version=?",
                title, bodyMd, id, expectedVersion);
        if (n == 0) {
            throw new com.ainative.v2.common.BizException("13005", "工作项已被修改,请重试");
        }
    }

    public void updateState(long id, long stateId, int expectedVersion) {
        int n = jdbc.update("UPDATE wi_ticket SET state_id=?, version=version+1 WHERE id=? AND deleted_at IS NULL AND version=?",
                stateId, id, expectedVersion);
        if (n == 0) {
            throw new com.ainative.v2.common.BizException("13005", "工作项已被修改,请重试");
        }
    }

    public void updateAssignee(long id, String assigneeType, Long assigneeId, Long squadId, Long sdlcId) {
        jdbc.update("UPDATE wi_ticket SET assignee_type=?, assignee_id=?, squad_id=COALESCE(?, squad_id), sdlc_id=COALESCE(?, sdlc_id), version=version+1 WHERE id=? AND deleted_at IS NULL",
                assigneeType, assigneeId, squadId, sdlcId, id);
    }

    public void softDelete(long id) {
        jdbc.update("UPDATE wi_ticket SET deleted_at=NOW() WHERE id=? AND deleted_at IS NULL", id);
    }

    public void restore(long id) {
        jdbc.update("UPDATE wi_ticket SET deleted_at=NULL WHERE id=?", id);
    }

    public record ListQuery(String keyword, String type, Long stateId, String assigneeType, Long assigneeId,
                            Integer priority, String tag, boolean recycled, int pageNum, int pageSize) {}

    public List<Ticket> list(ListQuery q) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE t.workspace_id=" + ws() + " AND t." + (q.recycled() ? "deleted_at IS NOT NULL" : "deleted_at IS NULL"));
        if (q.keyword() != null && !q.keyword().isBlank()) {
            where.append(" AND (t.title LIKE ? OR t.body_md LIKE ?)");
            String like = "%" + q.keyword().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (q.type() != null && !q.type().isBlank()) {
            where.append(" AND t.type = ?");
            args.add(q.type());
        }
        if (q.stateId() != null) {
            where.append(" AND t.state_id = ?");
            args.add(q.stateId());
        }
        if (q.assigneeType() != null && q.assigneeId() != null) {
            where.append(" AND t.assignee_type = ? AND t.assignee_id = ?");
            args.add(q.assigneeType());
            args.add(q.assigneeId());
        }
        if (q.priority() != null) {
            where.append(" AND t.priority = ?");
            args.add(q.priority());
        }
        if (q.tag() != null && !q.tag().isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM wi_ticket_tag g WHERE g.ticket_id=t.id AND g.tag=?)");
            args.add(q.tag().trim());
        }
        int pageSize = Math.max(1, Math.min(q.pageSize(), 100));
        int offset = (Math.max(1, q.pageNum()) - 1) * pageSize;
        args.add(pageSize);
        args.add(offset);
        return jdbc.query("SELECT " + TICKET_SELECT + " FROM wi_ticket t" + where + " ORDER BY t.gmt_modified DESC LIMIT ? OFFSET ?",
                WorkitemDao::mapTicket, args.toArray());
    }

    public long count(ListQuery q) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE t.workspace_id=" + ws() + " AND t." + (q.recycled() ? "deleted_at IS NOT NULL" : "deleted_at IS NULL"));
        if (q.keyword() != null && !q.keyword().isBlank()) {
            where.append(" AND (t.title LIKE ? OR t.body_md LIKE ?)");
            String like = "%" + q.keyword().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (q.type() != null && !q.type().isBlank()) {
            where.append(" AND t.type = ?");
            args.add(q.type());
        }
        if (q.stateId() != null) {
            where.append(" AND t.state_id = ?");
            args.add(q.stateId());
        }
        if (q.assigneeType() != null && q.assigneeId() != null) {
            where.append(" AND t.assignee_type = ? AND t.assignee_id = ?");
            args.add(q.assigneeType());
            args.add(q.assigneeId());
        }
        if (q.priority() != null) {
            where.append(" AND t.priority = ?");
            args.add(q.priority());
        }
        if (q.tag() != null && !q.tag().isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM wi_ticket_tag g WHERE g.ticket_id=t.id AND g.tag=?)");
            args.add(q.tag().trim());
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM wi_ticket t" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    // ── 标签 ──
    public List<String> tagsOf(long ticketId) {
        return jdbc.queryForList("SELECT tag FROM wi_ticket_tag WHERE ticket_id=? ORDER BY tag", String.class, ticketId);
    }

    public void replaceTags(long workspaceId, long ticketId, List<String> tags) {
        jdbc.update("DELETE FROM wi_ticket_tag WHERE ticket_id=?", ticketId);
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            jdbc.update("INSERT INTO wi_ticket_tag (workspace_id, ticket_id, tag) VALUES (?,?,?)",
                    workspaceId, ticketId, tag.trim());
        }
    }

    public List<String> tagOptions(long workspaceId) {
        return jdbc.queryForList("SELECT DISTINCT tag FROM wi_ticket_tag WHERE workspace_id=? ORDER BY tag", String.class, workspaceId);
    }

    // ── 状态机 ──
    public Optional<State> findState(long stateId) {
        List<State> rows = jdbc.query("SELECT id, flow_id, name, category, sort_no FROM wi_flow_state WHERE id=?",
                (rs, i) -> new State(rs.getLong("id"), rs.getLong("flow_id"), rs.getString("name"),
                        rs.getString("category"), rs.getInt("sort_no")), stateId);
        return rows.stream().findFirst();
    }

    public List<State> statesOfFlow(long flowId) {
        return jdbc.query("SELECT id, flow_id, name, category, sort_no FROM wi_flow_state WHERE flow_id=? ORDER BY sort_no",
                (rs, i) -> new State(rs.getLong("id"), rs.getLong("flow_id"), rs.getString("name"),
                        rs.getString("category"), rs.getInt("sort_no")), flowId);
    }

    public List<Edge> edgesOfFlow(long flowId) {
        return jdbc.query("SELECT from_state_id, to_state_id FROM wi_flow_edge WHERE flow_id=?",
                (rs, i) -> new Edge(rs.getLong(1), rs.getLong(2)), flowId);
    }

    public Optional<Long> defaultFlowId(long workspaceId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=? AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1",
                Long.class, workspaceId);
        return rows.stream().findFirst();
    }

    public Optional<Long> firstStateOfFlow(long flowId, String category) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM wi_flow_state WHERE flow_id=? AND category=? ORDER BY sort_no LIMIT 1",
                Long.class, flowId, category);
        return rows.stream().findFirst();
    }

    // ── 评论 ──
    public long insertNote(long workspaceId, long ticketId, String authorType, long authorId, String bodyMd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_note (workspace_id, ticket_id, author_type, author_id, body_md) VALUES (?,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workspaceId);
            ps.setLong(2, ticketId);
            ps.setString(3, authorType);
            ps.setLong(4, authorId);
            ps.setString(5, bodyMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Note> notesOf(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, author_type, author_id, body_md, gmt_create FROM wi_note WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> new Note(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), toLdt(rs.getTimestamp(6))), ticketId);
    }

    // ── 事件流水 ──
    public void addJournal(long workspaceId, long ticketId, String eventType, String actorType, Long actorId,
                           String fromVal, String toVal, String detailJson) {
        jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, actor_id, from_val, to_val, detail_json) VALUES (?,?,?,?,?,?,?,?)",
                workspaceId, ticketId, eventType, actorType, actorId, fromVal, toVal, detailJson);
    }

    public List<Journal> journalOf(long ticketId) {
        return jdbc.query("SELECT id, ticket_id, event_type, actor_type, actor_id, from_val, to_val, gmt_create FROM wi_journal WHERE ticket_id=? ORDER BY id",
                (rs, i) -> new Journal(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        nLong(rs, "actor_id"), rs.getString(6), rs.getString(7), toLdt(rs.getTimestamp(8))), ticketId);
    }

    // ── 关注 ──
    public void watch(long workspaceId, long ticketId, long userId) {
        jdbc.update("INSERT IGNORE INTO wi_subscriber (workspace_id, ticket_id, user_id) VALUES (?,?,?)",
                workspaceId, ticketId, userId);
    }

    public void unwatch(long ticketId, long userId) {
        jdbc.update("DELETE FROM wi_subscriber WHERE ticket_id=? AND user_id=?", ticketId, userId);
    }

    public List<Long> watchersOf(long ticketId) {
        return jdbc.queryForList("SELECT user_id FROM wi_subscriber WHERE ticket_id=? ORDER BY id", Long.class, ticketId);
    }
}
