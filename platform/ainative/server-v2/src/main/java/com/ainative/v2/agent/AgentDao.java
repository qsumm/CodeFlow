package com.ainative.v2.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 智能体域数据访问:agent/版本/环境引用/仓库权限/技能/记忆引用;执行器;MCP 令牌。 */
@Repository
public class AgentDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Agent(long id, String name, String roleCode, String roleName, String soulMd, String status) {}
    public record Executor(long id, long agentId, String name, String clientKind, String status, String lastHeartbeat) {}
    public record McpToken(long id, Long userId, String name, String tokenHash, String expiresAt) {}

    private final JdbcTemplate jdbc;

    public AgentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── Agent ──
    public Optional<Agent> findAgent(long id) {
        List<Agent> rows = jdbc.query("SELECT id, name, role_code, role_name, soul_md, status FROM ag_agent WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Agent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), id);
        return rows.stream().findFirst();
    }

    public List<Agent> listAgents() {
        return jdbc.query("SELECT id, name, role_code, role_name, soul_md, status FROM ag_agent WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> new Agent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)));
    }

    public long insertAgent(String name, String roleCode, String roleName, String soulMd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ag_agent (workspace_id, name, role_code, role_name, soul_md, status) VALUES (" + ws() + ",?,?,?,?, 'ACTIVE')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, roleCode);
            ps.setString(3, roleName);
            ps.setString(4, soulMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateAgentConfig(long id, String roleName, String soulMd) {
        jdbc.update("UPDATE ag_agent SET role_name=?, soul_md=?, version=version+1 WHERE id=? AND deleted_at IS NULL",
                roleName, soulMd, id);
    }

    public void deleteAgent(long id) {
        jdbc.update("UPDATE ag_agent SET deleted_at=NOW() WHERE id=?", id);
    }

    // ── 执行器 ──
    public List<Executor> executorsOf(long agentId) {
        return jdbc.query("SELECT id, agent_id, name, client_kind, status, last_heartbeat FROM ag_executor WHERE agent_id=? AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> new Executor(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), agentId);
    }

    public List<Executor> listExecutors() {
        return jdbc.query("SELECT id, agent_id, name, client_kind, status, last_heartbeat FROM ag_executor WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id",
                (rs, i) -> new Executor(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)));
    }

    public Optional<Executor> findExecutor(long id) {
        List<Executor> rows = jdbc.query("SELECT id, agent_id, name, client_kind, status, last_heartbeat FROM ag_executor WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Executor(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), id);
        return rows.stream().findFirst();
    }

    public long insertExecutor(long agentId, String name, String clientKind, String tokenHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ag_executor (workspace_id, agent_id, name, client_kind, token_hash, status) VALUES (" + ws() + ",?,?,?,?, 'OFFLINE')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, agentId);
            ps.setString(2, name);
            ps.setString(3, clientKind);
            ps.setString(4, tokenHash);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void deleteExecutor(long id) {
        jdbc.update("UPDATE ag_executor SET deleted_at=NOW() WHERE id=?", id);
    }

    public void updateExecutorToken(long id, String tokenHash) {
        jdbc.update("UPDATE ag_executor SET token_hash=? WHERE id=?", tokenHash, id);
    }

    public String modelPolicy(long id) {
        List<String> rows = jdbc.queryForList("SELECT model_policy FROM ag_executor WHERE id=?", String.class, id);
        return rows.isEmpty() ? "FOLLOW" : rows.get(0);
    }

    public void updateModelPolicy(long id, String policy) {
        jdbc.update("UPDATE ag_executor SET model_policy=? WHERE id=?", policy, id);
    }

    public void updateExecutorStatus(long id, String status) {
        jdbc.update("UPDATE ag_executor SET status=?, last_heartbeat=NOW() WHERE id=?", status, id);
    }

    // ── MCP 令牌 ──
    public List<McpToken> listMcpTokens(Long userId) {
        if (userId == null) {
            return jdbc.query("SELECT id, user_id, name, token_hash, expires_at FROM ag_mcp_token WHERE deleted_at IS NULL ORDER BY id DESC",
                    (rs, i) -> new McpToken(rs.getLong(1), nLong(rs, "user_id"), rs.getString(3), rs.getString(4), rs.getString(5)));
        }
        return jdbc.query("SELECT id, user_id, name, token_hash, expires_at FROM ag_mcp_token WHERE user_id=? AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new McpToken(rs.getLong(1), nLong(rs, "user_id"), rs.getString(3), rs.getString(4), rs.getString(5)), userId);
    }

    public long insertMcpToken(Long userId, String name, String tokenHash, String expiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ag_mcp_token (workspace_id, user_id, name, token_hash, expires_at) VALUES (" + ws() + ",?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            if (userId == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, tokenHash);
            ps.setString(4, expiresAt);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void revokeMcpToken(long id) {
        jdbc.update("UPDATE ag_mcp_token SET deleted_at=NOW() WHERE id=?", id);
    }
}
