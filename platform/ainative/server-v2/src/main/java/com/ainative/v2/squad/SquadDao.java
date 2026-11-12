package com.ainative.v2.squad;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 小队与流水线域数据访问(sq_ 表). */
@Repository
public class SquadDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Squad(long id, String name, String description, String status) {}
    public record Member(long agentId, String roleCode) {}
    public record Sdlc(long id, String name, String description, String status) {}
    public record SdlcStep(long id, long sdlcId, int seq, String name, String kind, Long agentId,
                           String instruction, boolean required) {}

    private final JdbcTemplate jdbc;

    public SquadDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── 小队 ──
    public Optional<Squad> findSquad(long id) {
        List<Squad> rows = jdbc.query("SELECT id, name, description, status FROM sq_squad WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Squad(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        return rows.stream().findFirst();
    }

    public List<Squad> listSquads() {
        return jdbc.query("SELECT id, name, description, status FROM sq_squad WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Squad(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public long insertSquad(String name, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sq_squad (workspace_id, name, description, status) VALUES (" + ws() + ",?,?, 'ACTIVE')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateSquad(long id, String name, String description) {
        jdbc.update("UPDATE sq_squad SET name=?, description=?, version=version+1 WHERE id=? AND deleted_at IS NULL",
                name, description, id);
    }

    public void deleteSquad(long id) {
        jdbc.update("UPDATE sq_squad SET deleted_at=NOW() WHERE id=?", id);
    }

    public List<Member> membersOf(long squadId) {
        return jdbc.query("SELECT agent_id, role_code FROM sq_member WHERE squad_id=? ORDER BY id",
                (rs, i) -> new Member(rs.getLong(1), rs.getString(2)), squadId);
    }

    public void addMember(long squadId, long agentId, String roleCode) {
        jdbc.update("INSERT IGNORE INTO sq_member (workspace_id, squad_id, agent_id, role_code) VALUES (1,?,?,?)",
                squadId, agentId, roleCode);
    }

    public void removeMember(long squadId, long agentId) {
        jdbc.update("DELETE FROM sq_member WHERE squad_id=? AND agent_id=?", squadId, agentId);
    }

    // ── 流水线 ──
    public Optional<Sdlc> findSdlc(long id) {
        List<Sdlc> rows = jdbc.query("SELECT id, name, description, status FROM sq_sdlc WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Sdlc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        return rows.stream().findFirst();
    }

    public List<Sdlc> listSdlcs() {
        return jdbc.query("SELECT id, name, description, status FROM sq_sdlc WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Sdlc(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public long insertSdlc(String name, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sq_sdlc (workspace_id, name, description, status) VALUES (" + ws() + ",?,?, 'DRAFT')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateSdlc(long id, String name, String description) {
        jdbc.update("UPDATE sq_sdlc SET name=?, description=?, version=version+1 WHERE id=? AND deleted_at IS NULL",
                name, description, id);
    }

    public void deleteSdlc(long id) {
        jdbc.update("UPDATE sq_sdlc SET deleted_at=NOW() WHERE id=?", id);
    }

    public void setSdlcStatus(long id, String status) {
        jdbc.update("UPDATE sq_sdlc SET status=?, version=version+1 WHERE id=?", status, id);
    }

    public List<SdlcStep> stepsOf(long sdlcId) {
        return jdbc.query("SELECT id, sdlc_id, seq, name, kind, agent_id, instruction, required FROM sq_sdlc_step WHERE sdlc_id=? ORDER BY seq",
                (rs, i) -> new SdlcStep(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), nLong(rs, "agent_id"), rs.getString(7), rs.getInt(8) == 1), sdlcId);
    }

    public long addStep(long sdlcId, int seq, String name, String kind, Long agentId, String instruction, boolean required) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sq_sdlc_step (workspace_id, sdlc_id, seq, name, kind, agent_id, instruction, required) VALUES (" + ws() + ",?,?,?,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sdlcId);
            ps.setInt(2, seq);
            ps.setString(3, name);
            ps.setString(4, kind);
            if (agentId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, agentId);
            ps.setString(6, instruction);
            ps.setInt(7, required ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateStep(long sdlcId, long stepId, String name, String kind, Long agentId, String instruction, boolean required) {
        jdbc.update("UPDATE sq_sdlc_step SET name=?, kind=?, agent_id=?, instruction=?, required=? WHERE id=? AND sdlc_id=?",
                name, kind, agentId, instruction, required ? 1 : 0, stepId, sdlcId);
    }

    public void deleteStep(long sdlcId, long stepId) {
        jdbc.update("DELETE FROM sq_sdlc_step WHERE id=? AND sdlc_id=?", stepId, sdlcId);
    }

    public void reorderSteps(long sdlcId, List<Long> stepIds) {
        int seq = 1;
        for (Long stepId : stepIds) {
            jdbc.update("UPDATE sq_sdlc_step SET seq=? WHERE id=? AND sdlc_id=?", seq++, stepId, sdlcId);
        }
    }
}
