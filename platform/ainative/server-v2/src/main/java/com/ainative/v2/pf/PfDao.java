package com.ainative.v2.pf;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 平台运维域数据访问(pf_ 表). */
@Repository
public class PfDao {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }


    public record Branding(String platformName, String themeKey, String primaryColor, String domain) {}
    public record Task(long id, String name, String cronExpr, boolean enabled) {}

    private final JdbcTemplate jdbc;

    public PfDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).longValue();
    }

    // ── 品牌 ──
    public Optional<Branding> branding() {
        List<Branding> rows = jdbc.query("SELECT platform_name, theme_key, primary_color, domain FROM pf_branding ORDER BY id LIMIT 1",
                (rs, i) -> new Branding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return rows.stream().findFirst();
    }

    public void upsertBranding(String name, String themeKey, String color, String domain) {
        int n = jdbc.update("UPDATE pf_branding SET platform_name=?, theme_key=?, primary_color=?, domain=?, version=version+1",
                name, themeKey, color, domain);
        if (n == 0) {
            jdbc.update("INSERT INTO pf_branding (platform_name, theme_key, primary_color, domain) VALUES (?,?,?,?)",
                    name, themeKey, color, domain);
        }
    }

    // ── 设置 ──
    public List<Map<String, Object>> settings(String group) {
        return jdbc.queryForList("SELECT setting_key AS `key`, value_json AS valueJson, is_secret AS isSecret FROM pf_setting WHERE workspace_id=" + ws() + " AND setting_group=? ORDER BY setting_key", group);
    }

    public void upsertSetting(String group, String key, String valueJson, boolean secret) {
        // value_json 是 JSON 列:统一 JSON_QUOTE 成 JSON 字符串字面量
        int n = jdbc.update("UPDATE pf_setting SET value_json=JSON_QUOTE(?), is_secret=?, version=version+1 WHERE workspace_id=" + ws() + " AND setting_group=? AND setting_key=?",
                valueJson, secret ? 1 : 0, group, key);
        if (n == 0) {
            jdbc.update("INSERT INTO pf_setting (workspace_id, setting_group, setting_key, value_json, is_secret) VALUES (" + ws() + ",?,?,JSON_QUOTE(?),?)",
                    group, key, valueJson, secret ? 1 : 0);
        }
    }

    // ── 审计 ──
    public List<Map<String, Object>> auditLogs(int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (Math.max(1, pageNum) - 1) * size;
        return jdbc.queryForList("SELECT id, actor_id AS actorId, action, target_type AS targetType, target_id AS targetId, gmt_create AS gmtCreate FROM pf_audit WHERE workspace_id=" + ws() + " ORDER BY id DESC LIMIT ? OFFSET ?", size, offset);
    }

    public long countAudit() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pf_audit WHERE workspace_id=" + ws() + "", Long.class);
        return n == null ? 0 : n;
    }

    public void audit(long actorId, String action, String targetType, Long targetId) {
        jdbc.update("INSERT INTO pf_audit (workspace_id, actor_id, action, target_type, target_id) VALUES (" + ws() + ",?,?,?,?)",
                actorId, action, targetType, targetId);
    }

    // ── 通知 ──
    public List<Map<String, Object>> notifications(long userId, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (Math.max(1, pageNum) - 1) * size;
        return jdbc.queryForList("SELECT id, event_type AS eventType, title, body_md AS bodyMd, read_flag AS readFlag, gmt_create AS gmtCreate FROM pf_notification WHERE user_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT ? OFFSET ?",
                userId, size, offset);
    }

    public long unreadCount(long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM pf_notification WHERE user_id=? AND read_flag=0 AND deleted_at IS NULL", Long.class, userId);
        return n == null ? 0 : n;
    }

    public void markRead(long id) {
        jdbc.update("UPDATE pf_notification SET read_flag=1 WHERE id=?", id);
    }

    public void markAllRead(long userId) {
        jdbc.update("UPDATE pf_notification SET read_flag=1 WHERE user_id=? AND read_flag=0", userId);
    }

    public void deleteNotification(long id) {
        jdbc.update("UPDATE pf_notification SET deleted_at=NOW() WHERE id=?", id);
    }

    public long insertNotification(long userId, String eventType, String title, String bodyMd) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pf_notification (workspace_id, user_id, event_type, title, body_md) VALUES (" + ws() + ",?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, eventType);
            ps.setString(3, title);
            ps.setString(4, bodyMd);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    // ── 定时任务 ──
    public List<Task> tasks() {
        return jdbc.query("SELECT id, name, cron_expr, enabled FROM pf_task WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC",
                (rs, i) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1));
    }

    public Optional<Task> findTask(long id) {
        List<Task> rows = jdbc.query("SELECT id, name, cron_expr, enabled FROM pf_task WHERE id=? AND deleted_at IS NULL",
                (rs, i) -> new Task(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1), id);
        return rows.stream().findFirst();
    }

    public long insertTask(String name, String cron, String templateJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pf_task (workspace_id, name, cron_expr, ticket_template_json, enabled) VALUES (" + ws() + ",?,?,?,0)",
                    PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, cron);
            ps.setString(3, templateJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public void updateTask(long id, String name, String cron, String templateJson) {
        jdbc.update("UPDATE pf_task SET name=?, cron_expr=?, ticket_template_json=?, version=version+1 WHERE id=?",
                name, cron, templateJson, id);
    }

    public void setTaskEnabled(long id, boolean enabled) {
        jdbc.update("UPDATE pf_task SET enabled=?, version=version+1 WHERE id=?", enabled ? 1 : 0, id);
    }

    public void deleteTask(long id) {
        jdbc.update("UPDATE pf_task SET deleted_at=NOW() WHERE id=?", id);
    }

    public List<Map<String, Object>> taskRuns(long taskId) {
        return jdbc.queryForList("SELECT id, task_id AS taskId, status, started_at AS startedAt, ended_at AS endedAt FROM pf_task_run WHERE task_id=? ORDER BY id DESC", taskId);
    }

    // ── IM 渠道 ──
    public List<Map<String, Object>> imChannels() {
        return jdbc.queryForList("SELECT channel, app_key AS appKey, enabled FROM pf_im_config ORDER BY channel");
    }

    public void upsertImChannel(String channel, String appKey, boolean enabled) {
        int n = jdbc.update("UPDATE pf_im_config SET app_key=?, enabled=? WHERE channel=?", appKey, enabled ? 1 : 0, channel);
        if (n == 0) {
            jdbc.update("INSERT INTO pf_im_config (channel, app_key, enabled) VALUES (?,?,?)", channel, appKey, enabled ? 1 : 0);
        }
    }

    public Optional<Map<String, Object>> imSelection() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT selected_channel AS provider, generation FROM pf_im_selection ORDER BY id LIMIT 1");
        return rows.stream().findFirst();
    }

    public void setImSelection(String channel) {
        int n = jdbc.update("UPDATE pf_im_selection SET selected_channel=?, generation=generation+1", channel);
        if (n == 0) {
            jdbc.update("INSERT INTO pf_im_selection (selected_channel, generation) VALUES (?,1)", channel);
        }
    }

    // ── 自进化 / 备份 / 调试日志 ──
    public List<Map<String, Object>> evolutionProposals() {
        return jdbc.queryForList("SELECT id, title, status FROM pf_evolution_proposal WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC");
    }

    public List<Map<String, Object>> backups() {
        return jdbc.queryForList("SELECT id, name, status, gmt_create AS gmtCreate FROM pf_backup WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY id DESC");
    }

    public List<Map<String, Object>> debugLogs(long ticketId) {
        return jdbc.queryForList("SELECT id, ticket_id AS ticketId, content_md AS contentMd, gmt_create AS gmtCreate FROM pf_debug_log WHERE ticket_id=? ORDER BY id DESC", ticketId);
    }
}
