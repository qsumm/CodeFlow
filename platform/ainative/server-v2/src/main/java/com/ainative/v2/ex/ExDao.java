package com.ainative.v2.ex;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 外部连接域数据访问(ex_ 表):绑定/状态映射/通知偏好/机器人绑定。 */
@Repository
public class ExDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Binding(long id, long workspaceId, String provider, String projectId, String name, String status, String configJson) {}
    public record StatusMap(long id, long bindingId, String externalStatus, long stateId) {}

    private final JdbcTemplate jdbc;

    public ExDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── 绑定 ──
    public List<Binding> bindings(String provider) {
        return jdbc.query("SELECT id, workspace_id, provider, project_id, name, status, config_json FROM ex_binding WHERE workspace_id=" + ws() + " AND provider=? AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Binding(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), provider);
    }

    public Optional<Binding> findBinding(long id) {
        List<Binding> rows = jdbc.query("SELECT id, workspace_id, provider, project_id, name, status, config_json FROM ex_binding WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Binding(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), id);
        return rows.stream().findFirst();
    }

    public long insertBinding(String provider, String projectId, String name, String credentialRef, String configJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ex_binding (workspace_id, provider, project_id, name, credential_ref, config_json, status) VALUES (" + ws() + ",?,?,?,?,?, 'ACTIVE')",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, provider);
            ps.setString(2, projectId);
            ps.setString(3, name);
            ps.setString(4, credentialRef);
            ps.setString(5, configJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void deleteBinding(long id) {
        jdbc.update("UPDATE ex_binding SET deleted_at=NOW() WHERE id=?", id);
    }

    // ── 状态映射 ──
    public List<StatusMap> statusMaps(long bindingId) {
        return jdbc.query("SELECT id, binding_id, external_status, state_id FROM ex_status_map WHERE binding_id=? ORDER BY id",
                (rs, i) -> new StatusMap(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getLong("state_id")), bindingId);
    }

    public void upsertStatusMap(long bindingId, String externalStatus, long stateId) {
        int n = jdbc.update("UPDATE ex_status_map SET state_id=? WHERE binding_id=? AND external_status=?",
                stateId, bindingId, externalStatus);
        if (n == 0) {
            jdbc.update("INSERT INTO ex_status_map (workspace_id, binding_id, external_status, state_id) VALUES (" + ws() + ",?,?,?)",
                    bindingId, externalStatus, stateId);
        }
    }

    // ── 通知偏好 ──
    public List<java.util.Map<String, Object>> prefsOf(long userId) {
        return jdbc.queryForList("SELECT event_type AS eventType, in_app AS inApp, im_channel AS imChannel FROM ex_notify_pref WHERE user_id=? ORDER BY event_type", userId);
    }

    public void upsertPref(long userId, String eventType, boolean inApp, boolean imChannel) {
        int n = jdbc.update("UPDATE ex_notify_pref SET in_app=?, im_channel=? WHERE user_id=? AND event_type=?",
                inApp ? 1 : 0, imChannel ? 1 : 0, userId, eventType);
        if (n == 0) {
            jdbc.update("INSERT INTO ex_notify_pref (workspace_id, user_id, event_type, in_app, im_channel) VALUES (" + ws() + ",?,?,?,?)",
                    userId, eventType, inApp ? 1 : 0, imChannel ? 1 : 0);
        }
    }
}
