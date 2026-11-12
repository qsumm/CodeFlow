package com.ainative.v2.cv;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 会话澄清域数据访问:澄清结论(cv_clarification)+ 澄清会话(ag_chat* 存储)。 */
@Repository
public class CvDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Clarification(long id, long ticketId, String contentMd, String status) {}
    public record Chat(long id, long agentId, String title, String status) {}
    public record Turn(long id, long chatId, int seq, String role, String contentMd) {}

    private final JdbcTemplate jdbc;

    public CvDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── 澄清结论 ──
    public Optional<Clarification> findClarification(long ticketId) {
        List<Clarification> rows = jdbc.query(
                "SELECT id, ticket_id, content_md, status FROM cv_clarification WHERE ticket_id=?",
                (rs, i) -> new Clarification(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), ticketId);
        return rows.stream().findFirst();
    }

    public void upsertClarification(long ticketId, String contentMd, String status) {
        int n = jdbc.update("UPDATE cv_clarification SET content_md=?, status=?, version=version+1 WHERE ticket_id=?",
                contentMd, status, ticketId);
        if (n == 0) {
            jdbc.update("INSERT INTO cv_clarification (workspace_id, ticket_id, content_md, status) VALUES (1,?,?,?)",
                    ticketId, contentMd, status);
        }
    }

    // ── 澄清会话(ag_chat) ──
    public List<Chat> chatsOfAgent(long agentId) {
        return jdbc.query("SELECT id, agent_id, title, status FROM ag_chat WHERE agent_id=? AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Chat(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), agentId);
    }

    public Optional<Chat> findChat(long id) {
        List<Chat> rows = jdbc.query("SELECT id, agent_id, title, status FROM ag_chat WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Chat(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), id);
        return rows.stream().findFirst();
    }

    public long insertChat(long agentId, String title, Long userId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ag_chat (workspace_id, agent_id, title, user_id, status) VALUES (1,?,?,?, 'OPEN')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, agentId);
            ps.setString(2, title);
            if (userId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, userId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public List<Turn> turnsOf(long chatId) {
        return jdbc.query("SELECT id, chat_id, seq, role, content_md FROM ag_chat_turn WHERE chat_id=? ORDER BY seq",
                (rs, i) -> new Turn(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)), chatId);
    }

    public int nextTurnSeq(long chatId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM ag_chat_turn WHERE chat_id=?", Integer.class, chatId);
        return (max == null ? 0 : max) + 1;
    }

    public long appendTurn(long chatId, int seq, String role, String contentMd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md) VALUES (1,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, chatId);
            ps.setInt(2, seq);
            ps.setString(3, role);
            ps.setString(4, contentMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    // ── 额度/用量(最小实现) ──
    public void recordUsage(long ticketId, long tokensIn, long tokensOut, String model) {
        jdbc.update("INSERT INTO cv_usage (workspace_id, ticket_id, tokens_in, tokens_out, model) VALUES (" + ws() + ",?,?,?,?)",
                ticketId, tokensIn, tokensOut, model);
    }

    public List<java.util.Map<String, Object>> usageOf(long ticketId) {
        return jdbc.queryForList("SELECT id, ticket_id AS ticketId, tokens_in AS tokensIn, tokens_out AS tokensOut, model, gmt_create AS gmtCreate FROM cv_usage WHERE ticket_id=? ORDER BY id DESC", ticketId);
    }
}
